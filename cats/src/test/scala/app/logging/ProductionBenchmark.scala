package app.logging

import app.domain.{OrderId, TransactionId}
import app.http.ChargeResponse
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import scribe.mdc.MDC
import scribe.output.LogOutput
import scribe.output.format.{ASCIIOutputFormat, OutputFormat}
import scribe.writer.Writer
import scribe.{Level, LogFeature, LogRecord, Logger, Scribe, data}

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.atomic.{AtomicInteger, LongAdder}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Your Log[IO] + LogContext vs scribe-cats, in a simulated HTTP service. Every
  * variant uses the same output pipeline (JsonLogFormat.formatter +
  * AsyncStdoutWriter, stdout -> /dev/null), so only the logging API differs.
  *
  * {{{
  * Usage: ProductionBenchmark <variant> <scenario> [verify] [--json <file>]
  *   variants:  custom            Log[IO] + LogContext.scoped (fiber-local context)
  *              scribe-cats-data  scribe.cats.io, context passed as data(...) on every call
  *              scribe-cats-mdc   scribe.cats.io, context in Scribe's thread-local MDC
  *              none              no logging (baseline)
  *   scenarios: requests   256 concurrent fibers handling requests: 2 INFO + 4 disabled DEBUG each, a 1ms "DB call",
  *                         circe response encoding; 2% WARN, 0.5% ERROR with a stack trace
  *              debug-hot  one fiber calling a disabled log.debug in a tight loop
  *   verify:    check every log line carries the requestId of the request that logged it (no stdout output)
  *   --json:    also append the result as one JSON line to <file> (used by bench/run.sh and bench/report.py)
  * }}}
  */
object ProductionBenchmark {
  private val LoggerName = "app.payments.PaymentService"
  private val Concurrency = 256

  private trait Api {
    def scope[A](reqId: String)(io: IO[A]): IO[A]
    def info(reqId: String, msg: String, fields: LogFeature*): IO[Unit]
    def debug(reqId: String, msg: => String): IO[Unit]
    def warn(reqId: String, msg: String, fields: LogFeature*): IO[Unit]
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogFeature*
    ): IO[Unit]
  }

  private def text(msg: => String): LogFeature =
    LogFeature.string2LoggableMessage(msg)

  /** In verify mode every call also carries the expected requestId, which
    * CheckingWriter compares.
    */
  private final class Verify(enabled: Boolean) {
    def apply(reqId: String): Seq[LogFeature] =
      if (enabled) Seq(data("expected", reqId)) else Nil
  }

  private final class Custom(log: Log[IO], ctx: LogContext, v: Verify)
      extends Api {
    def scope[A](reqId: String)(io: IO[A]): IO[A] =
      ctx.scoped(
        "requestId" -> reqId,
        "method" -> "POST",
        "path" -> "/orders/charge"
      )(io)
    def info(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      log.info((text(msg) +: (fields ++ v(reqId)))*)
    def debug(reqId: String, msg: => String): IO[Unit] =
      log.debug((text(msg) +: v(reqId))*)
    def warn(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      log.warn((text(msg) +: (fields ++ v(reqId)))*)
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogFeature*
    ): IO[Unit] =
      log.error(
        (text(msg) +: LogFeature
          .throwable2LoggableMessage(t) +: (fields ++ v(reqId)))*
      )
  }

  /** The correct way with scribe-cats under fibers: every call site passes the
    * context explicitly.
    */
  private final class ScribeCatsData(v: Verify) extends Api {
    private val s: Scribe[IO] = scribe.cats.io
    private def ctx(reqId: String): Seq[LogFeature] =
      Seq(
        data("requestId", reqId),
        data("method", "POST"),
        data("path", "/orders/charge")
      )
    def scope[A](reqId: String)(io: IO[A]): IO[A] = io
    def info(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      s.info((text(msg) +: (ctx(reqId) ++ fields ++ v(reqId)))*)
    def debug(reqId: String, msg: => String): IO[Unit] =
      s.debug((text(msg) +: (ctx(reqId) ++ v(reqId)))*)
    def warn(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      s.warn((text(msg) +: (ctx(reqId) ++ fields ++ v(reqId)))*)
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogFeature*
    ): IO[Unit] =
      s.error(
        (text(msg) +: LogFeature
          .throwable2LoggableMessage(t) +: (ctx(reqId) ++ fields ++ v(reqId)))*
      )
  }

  /** The tempting way: set the MDC once per request, like you would with a
    * thread-per-request server.
    */
  private final class ScribeCatsMdc(v: Verify) extends Api {
    private val s: Scribe[IO] = scribe.cats.io
    def scope[A](reqId: String)(io: IO[A]): IO[A] =
      (IO {
        MDC("requestId") = reqId
        MDC("method") = "POST"
        MDC("path") = "/orders/charge": Unit
      } *> io).guarantee(IO {
        MDC.remove("requestId")
        MDC.remove("method")
        MDC.remove("path"): Unit
      })
    def info(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      s.info((text(msg) +: (fields ++ v(reqId)))*)
    def debug(reqId: String, msg: => String): IO[Unit] =
      s.debug((text(msg) +: v(reqId))*)
    def warn(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      s.warn((text(msg) +: (fields ++ v(reqId)))*)
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogFeature*
    ): IO[Unit] =
      s.error(
        (text(msg) +: LogFeature
          .throwable2LoggableMessage(t) +: (fields ++ v(reqId)))*
      )
  }

  private object NoLogging extends Api {
    def scope[A](reqId: String)(io: IO[A]): IO[A] = io
    def info(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      IO.unit
    def debug(reqId: String, msg: => String): IO[Unit] = IO.unit
    def warn(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      IO.unit
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogFeature*
    ): IO[Unit] = IO.unit
  }

  /** Verify mode: compares the requestId each line would carry in its JSON with
    * the one the request logged with.
    */
  private object CheckingWriter extends Writer {
    val checked = new LongAdder
    val missing = new LongAdder
    val wrong = new LongAdder
    override def write(
        record: LogRecord,
        output: LogOutput,
        outputFormat: OutputFormat
    ): Unit = {
      val fields = LogJsonFields.data(
        record
      ) // what JsonLogFormat writes: thread-local MDC + record data
      fields.get("expected").map(_()).foreach { expected =>
        checked.increment()
        fields.get("requestId").map(_()) match {
          case None                         => missing.increment()
          case Some(got) if got != expected => wrong.increment()
          case _                            => ()
        }
      }
    }
  }

  private val gatewayFailure: Throwable = {
    def deep(n: Int): Throwable =
      if (n == 0)
        new IllegalStateException(
          "gateway failed",
          new java.net.SocketTimeoutException("Read timed out")
        )
      else deep(n - 1)
    deep(25)
  }

  /** One HTTP request, logging roughly like a real handler. */
  private def request(api: Api, i: Int): IO[Unit] = {
    val reqId = s"req-$i"
    val orderId = s"ord-$i"
    api.scope(reqId) {
      api.info(reqId, "request received", data("orderId", orderId)) *>
        api.debug(reqId, s"parsing body for $orderId") *>
        api.debug(reqId, s"loading order $orderId from db") *>
        IO.sleep(
          1.millis
        ) *> // DB call: the fiber usually resumes on a different worker thread
        api.debug(reqId, s"order $orderId loaded: 3 items, total 4748") *>
        IO(
          ChargeResponse(
            OrderId(orderId),
            "approved",
            Some(TransactionId(s"tx-$i")),
            None
          ).asJson.noSpaces
        )
          .flatMap { body =>
            api.debug(reqId, s"response body ${body.length} bytes") *>
              (if (i % 200 == 0)
                 api.error(
                   reqId,
                   "payment gateway call failed",
                   gatewayFailure,
                   data("orderId", orderId)
                 )
               else if (i % 50 == 0)
                 api.warn(
                   reqId,
                   "payment declined",
                   data("orderId", orderId),
                   data("declineReason", "InsufficientFunds")
                 )
               else
                 api.info(
                   reqId,
                   "order charged",
                   data("orderId", orderId),
                   data("amountCents", 4748L),
                   data("durationMs", 41L)
                 ))
          }
    }
  }

  private def serve(
      api: Api,
      from: Int,
      count: Int,
      latencies: Array[Long]
  ): IO[Unit] = {
    val next = new AtomicInteger(0)
    def worker: IO[Unit] = IO(next.getAndIncrement()).flatMap { n =>
      if (n >= count) IO.unit
      else
        IO.monotonic.flatMap { start =>
          request(api, from + n) *> IO.monotonic
            .map(end => latencies(n) = (end - start).toNanos)
        } *> worker
    }
    List.fill(Concurrency)(worker).parSequence_
  }

  private final case class Counters(
      cpuNanos: Long,
      allocated: Long,
      gcCount: Long,
      gcMillis: Long
  )

  private def counters(): Counters = {
    val threads = ManagementFactory.getThreadMXBean
      .asInstanceOf[com.sun.management.ThreadMXBean]
    val os = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
    val gcs = ManagementFactory.getGarbageCollectorMXBeans.asScala
    Counters(
      os.getProcessCpuTime,
      threads.getTotalThreadAllocatedBytes,
      gcs.map(_.getCollectionCount).sum,
      gcs.map(_.getCollectionTime).sum
    )
  }

  /** One JSON line per run:
    * {"scenario":..,"variant":..,"cores":..,"java":..,"metrics":{..}}.
    */
  private def appendJson(
      path: Option[String],
      scenario: String,
      variant: String,
      metrics: (String, Double)*
  ): Unit =
    path.foreach { p =>
      val fields = metrics.map((k, v) => s""""$k":$v""").mkString(",")
      val line = s"""{"scenario":"$scenario","variant":"$variant",""" +
        s""""cores":${Runtime.getRuntime.availableProcessors},"java":"${System
            .getProperty("java.version")}",""" +
        s""""metrics":{$fields}}\n"""
      Files.writeString(
        Paths.get(p),
        line,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      ): Unit
    }

  def main(args: Array[String]): Unit = {
    val variant = args(0)
    val scenario = args(1)
    val verify = args.contains("verify")
    val jsonOut = args.indexOf("--json") match {
      case -1 => None
      case i  => args.lift(i + 1)
    }
    val v = new Verify(verify)

    val stdout = new AsyncStdoutWriter(65536)
    Logger.root
      .clearHandlers()
      .clearModifiers()
      .withMinimumLevel(Level.Info) // production level: DEBUG is off
      .withHandler(
        formatter = JsonLogFormat.formatter,
        writer = if (verify) CheckingWriter else stdout,
        outputFormat = ASCIIOutputFormat
      )
      .replace(): Unit

    val ctx = LogContext.create.unsafeRunSync()
    val api: Api = variant match {
      case "custom" => new Custom(Log.named[IO](LoggerName, ctx), ctx, v)
      case "scribe-cats-data" => new ScribeCatsData(v)
      case "scribe-cats-mdc"  => new ScribeCatsMdc(v)
      case "none"             => NoLogging
      case other              => sys.error(s"Unknown variant: $other")
    }

    scenario match {
      case "requests" =>
        val (warmup, measured) = (60000, 300000)
        serve(api, 0, warmup, new Array[Long](warmup)).unsafeRunSync()
        Thread.sleep(2000)
        val latencies = new Array[Long](measured)
        val droppedBefore = stdout.dropped
        val c0 = counters()
        val t0 = System.nanoTime()
        serve(api, warmup, measured, latencies).unsafeRunSync()
        val t1 = System.nanoTime()
        val c1 = counters()
        stdout.close()
        java.util.Arrays.sort(latencies)
        def pct(p: Double): Double =
          latencies(math.min(measured - 1, (p * measured).toInt)) / 1e6
        val seconds = (t1 - t0) / 1e9
        if (verify) {
          System.err.println(
            f"$variant%-17s verify: ${CheckingWriter.checked.sum()}%,d lines checked, " +
              f"${CheckingWriter.missing.sum()}%,d missing requestId, ${CheckingWriter.wrong.sum()}%,d wrong requestId"
          )
          appendJson(
            jsonOut,
            "verify",
            variant,
            "checked" -> CheckingWriter.checked.sum().toDouble,
            "missing" -> CheckingWriter.missing.sum().toDouble,
            "wrong" -> CheckingWriter.wrong.sum().toDouble
          )
        } else {
          appendJson(
            jsonOut,
            "requests",
            variant,
            "reqPerSec" -> measured / seconds,
            "p50Ms" -> pct(0.50),
            "p99Ms" -> pct(0.99),
            "p999Ms" -> pct(0.999),
            "cpuUsPerReq" -> (c1.cpuNanos - c0.cpuNanos) / 1e3 / measured,
            "allocKbPerReq" -> (c1.allocated - c0.allocated) / 1024.0 / measured,
            "gcCount" -> (c1.gcCount - c0.gcCount).toDouble,
            "gcMs" -> (c1.gcMillis - c0.gcMillis).toDouble,
            "dropped" -> (stdout.dropped - droppedBefore).toDouble
          )
          System.err.println(
            f"$variant%-17s ${measured / seconds}%,8.0f req/s  p50 ${pct(0.50)}%5.2f ms  p99 ${pct(0.99)}%5.2f ms  " +
              f"p999 ${pct(0.999)}%5.2f ms  CPU ${(c1.cpuNanos - c0.cpuNanos) / 1e3 / measured}%5.1f µs/req  " +
              f"alloc ${(c1.allocated - c0.allocated) / 1024.0 / measured}%5.1f KB/req  " +
              f"GC ${c1.gcCount - c0.gcCount}%3d (${c1.gcMillis - c0.gcMillis}%4d ms)  dropped ${stdout.dropped - droppedBefore}"
          )
        }

      case "debug-hot" =>
        def loop(i: Int, n: Int): IO[Unit] =
          if (i == n) IO.unit
          else api.debug("req-1", s"value $i") >> loop(i + 1, n)
        val body = (n: Int) => api.scope("req-1")(loop(0, n))
        body(2000000).unsafeRunSync() // warm-up
        val n = 5000000
        val threads = ManagementFactory.getThreadMXBean
          .asInstanceOf[com.sun.management.ThreadMXBean]
        val a0 = threads.getTotalThreadAllocatedBytes
        val t0 = System.nanoTime()
        body(n).unsafeRunSync()
        val t1 = System.nanoTime()
        val a1 = threads.getTotalThreadAllocatedBytes
        stdout.close()
        appendJson(
          jsonOut,
          "debug-hot",
          variant,
          "nsPerCall" -> (t1 - t0).toDouble / n,
          "bytesPerCall" -> (a1 - a0).toDouble / n
        )
        System.err.println(
          f"$variant%-17s disabled debug: ${(t1 - t0).toDouble / n}%6.1f ns/call  ${(a1 - a0).toDouble / n}%6.1f bytes/call"
        )

      case other => sys.error(s"Unknown scenario: $other")
    }
  }
}
