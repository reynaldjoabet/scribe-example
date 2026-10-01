package app.logging

import app.domain.{OrderId, TransactionId}
import app.http.ChargeResponse
import app.logging.BenchSupport.*
import io.circe.syntax.*
import zio.*

import java.util.concurrent.atomic.AtomicInteger

/** The ZIO side of the cats vs ZIO comparison: ZIO.log* + ZIO.logAnnotate with
  * ScribeZLogger as the backend, on the shared workload and output pipeline in
  * BenchSupport. The cats-effect side is CatsProductionBenchmark.
  *
  * {{{
  * Usage: ZioProductionBenchmark <logging|baseline> <requests|debug-hot> [verify] [--json <file>]
  *   logging    ZIO.logAnnotate + ZIO.log* -> ScribeZLogger (variant "zio")
  *   baseline   same workload without logging (variant "zio-baseline", the ZIO baseline)
  * }}}
  */
object ZioProductionBenchmark {
  private final class RequestLogger(verify: Boolean) {
    private def withExpected(reqId: String)(z: UIO[Unit]): UIO[Unit] =
      if (verify) ZIO.logAnnotate("expected", reqId)(z) else z
    private def withFields(fields: Seq[LogAnnotation])(
        z: UIO[Unit]
    ): UIO[Unit] =
      if (fields.isEmpty) z else ZIO.logAnnotate(fields.toSet)(z)

    def scope[A](reqId: String)(z: UIO[A]): UIO[A] =
      ZIO.logAnnotate(
        LogAnnotation("requestId", reqId),
        LogAnnotation("method", "POST"),
        LogAnnotation("path", "/orders/charge")
      )(z)
    def info(reqId: String, msg: String, fields: LogAnnotation*): UIO[Unit] =
      withExpected(reqId)(withFields(fields)(ZIO.logInfo(msg)))
    def debug(reqId: String, msg: => String): UIO[Unit] =
      withExpected(reqId)(ZIO.logDebug(msg))
    def warn(reqId: String, msg: String, fields: LogAnnotation*): UIO[Unit] =
      withExpected(reqId)(withFields(fields)(ZIO.logWarning(msg)))
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogAnnotation*
    ): UIO[Unit] =
      withExpected(reqId)(
        withFields(fields)(ZIO.logErrorCause(msg, Cause.fail(t)))
      )
  }

  private def request(logger: Option[RequestLogger], i: Int): UIO[Unit] = {
    val reqId = s"req-$i"
    val orderId = s"ord-$i"
    def log(f: RequestLogger => UIO[Unit]): UIO[Unit] = logger.fold(ZIO.unit)(f)
    val handler =
      log(
        _.info(reqId, "request received", LogAnnotation("orderId", orderId))
      ) *>
        log(_.debug(reqId, s"parsing body for $orderId")) *>
        log(_.debug(reqId, s"loading order $orderId from db")) *>
        ZIO.sleep(
          1.millis
        ) *> // DB call: the fiber usually resumes on a different worker thread
        log(_.debug(reqId, s"order $orderId loaded: 3 items, total 4748")) *>
        ZIO
          .succeed(
            ChargeResponse(
              OrderId(orderId),
              "approved",
              Some(TransactionId(s"tx-$i")),
              None
            ).asJson.noSpaces
          )
          .flatMap { body =>
            log(_.debug(reqId, s"response body ${body.length} bytes")) *>
              (if (i % 200 == 0)
                 log(
                   _.error(
                     reqId,
                     "payment gateway call failed",
                     gatewayFailure,
                     LogAnnotation("orderId", orderId)
                   )
                 )
               else if (i % 50 == 0)
                 log(
                   _.warn(
                     reqId,
                     "payment declined",
                     LogAnnotation("orderId", orderId),
                     LogAnnotation("declineReason", "InsufficientFunds")
                   )
                 )
               else
                 log(
                   _.info(
                     reqId,
                     "order charged",
                     LogAnnotation("orderId", orderId),
                     LogAnnotation("amountCents", "4748"),
                     LogAnnotation("durationMs", "41")
                   )
                 ))
          }
    logger.fold(handler)(_.scope(reqId)(handler))
  }

  private def serve(
      logger: Option[RequestLogger],
      from: Int,
      count: Int,
      latencies: Array[Long]
  ): UIO[Unit] = {
    val next = new AtomicInteger(0)
    def worker: UIO[Unit] = ZIO.suspendSucceed {
      val n = next.getAndIncrement()
      if (n >= count) ZIO.unit
      else
        Clock.nanoTime.flatMap { start =>
          request(logger, from + n) *> Clock.nanoTime
            .map(end => latencies(n) = end - start)
        } *> worker
    }
    ZIO.collectAllParDiscard(List.fill(Concurrency)(worker))
  }

  // ZIO's default console logger replaced by ScribeZLogger, as in the app (ZioLogging.scribeLogger)
  private val runtime: Runtime[Any] =
    Unsafe.unsafe(implicit u =>
      Runtime.unsafe.fromLayer(ZioLogging.scribeLogger)
    )

  private def run[A](z: UIO[A]): A =
    Unsafe.unsafe(implicit u => runtime.unsafe.run(z).getOrThrowFiberFailure())

  def main(argv: Array[String]): Unit = {
    val args = parseArgs(argv)
    val variant = if (args.logging) "zio" else "zio-baseline"
    val stdout = setupScribe(args.verify)
    val logger =
      if (args.logging) Some(new RequestLogger(args.verify)) else None

    args.scenario match {
      case "requests" =>
        run(serve(logger, 0, WarmupRequests, new Array[Long](WarmupRequests)))
        Thread.sleep(2000)
        val latencies = new Array[Long](MeasuredRequests)
        val droppedBefore = stdout.dropped
        val c0 = counters()
        val t0 = java.lang.System.nanoTime()
        run(serve(logger, WarmupRequests, MeasuredRequests, latencies))
        val t1 = java.lang.System.nanoTime()
        val c1 = counters()
        stdout.close()
        if (args.verify) reportVerify(variant, args)
        else
          reportRequests(
            variant,
            args,
            latencies,
            t1 - t0,
            c0,
            c1,
            stdout.dropped - droppedBefore
          )

      case "debug-hot" =>
        def loop(i: Int, n: Int): UIO[Unit] =
          if (i == n) ZIO.unit
          else
            logger
              .fold(ZIO.unit)(_.debug("req-1", s"value $i")) *> loop(i + 1, n)
        def body(n: Int): UIO[Unit] =
          logger.fold(loop(0, n))(_.scope("req-1")(loop(0, n)))
        run(body(DebugWarmup))
        val a0 = allocatedBytes()
        val t0 = java.lang.System.nanoTime()
        run(body(DebugCalls))
        val t1 = java.lang.System.nanoTime()
        val a1 = allocatedBytes()
        stdout.close()
        reportDebugHot(variant, args, t1 - t0, a1 - a0)

      case other => sys.error(s"Unknown scenario: $other")
    }
  }
}
