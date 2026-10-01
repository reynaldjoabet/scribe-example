package app.logging

import app.domain.{OrderId, TransactionId}
import app.http.ChargeResponse
import app.logging.BenchSupport.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.circe.syntax.*
import scribe.{LogFeature, data}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** The cats-effect side of the cats vs ZIO comparison: Log[IO] + LogContext, on
  * the shared workload and output pipeline in BenchSupport. The ZIO side is
  * ZioProductionBenchmark.
  *
  * {{{
  * Usage: CatsProductionBenchmark <logging|baseline> <requests|debug-hot> [verify] [--json <file>]
  *   logging    Log[IO] + LogContext.scoped (variant "cats")
  *   baseline   same workload without logging (variant "cats-baseline", the cats-effect baseline)
  * }}}
  */
object CatsProductionBenchmark {
  private val LoggerName = "app.payments.PaymentService"

  private final class RequestLogger(
      log: Log[IO],
      ctx: LogContext,
      verify: Boolean
  ) {
    private def expected(reqId: String): Seq[LogFeature] =
      if (verify) Seq(data("expected", reqId)) else Nil
    private def text(msg: => String): LogFeature =
      LogFeature.string2LoggableMessage(msg)

    def scope[A](reqId: String)(io: IO[A]): IO[A] =
      ctx.scoped(
        "requestId" -> reqId,
        "method" -> "POST",
        "path" -> "/orders/charge"
      )(io)
    def info(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      log.info((text(msg) +: (fields ++ expected(reqId)))*)
    // Without verify, exactly what app code writes: log.debug("text")
    def debug(reqId: String, msg: => String): IO[Unit] =
      if (verify) log.debug(text(msg), data("expected", reqId))
      else log.debug(msg)
    def warn(reqId: String, msg: String, fields: LogFeature*): IO[Unit] =
      log.warn((text(msg) +: (fields ++ expected(reqId)))*)
    def error(
        reqId: String,
        msg: String,
        t: Throwable,
        fields: LogFeature*
    ): IO[Unit] =
      log.error(
        (text(msg) +: LogFeature.throwable2LoggableMessage(
          t
        ) +: (fields ++ expected(reqId)))*
      )
  }

  private def request(logger: Option[RequestLogger], i: Int): IO[Unit] = {
    val reqId = s"req-$i"
    val orderId = s"ord-$i"
    def log(f: RequestLogger => IO[Unit]): IO[Unit] = logger.fold(IO.unit)(f)
    val handler =
      log(_.info(reqId, "request received", data("orderId", orderId))) *>
        log(_.debug(reqId, s"parsing body for $orderId")) *>
        log(_.debug(reqId, s"loading order $orderId from db")) *>
        IO.sleep(
          1.millis
        ) *> // DB call: the fiber usually resumes on a different worker thread
        log(_.debug(reqId, s"order $orderId loaded: 3 items, total 4748")) *>
        IO(
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
                     data("orderId", orderId)
                   )
                 )
               else if (i % 50 == 0)
                 log(
                   _.warn(
                     reqId,
                     "payment declined",
                     data("orderId", orderId),
                     data("declineReason", "InsufficientFunds")
                   )
                 )
               else
                 log(
                   _.info(
                     reqId,
                     "order charged",
                     data("orderId", orderId),
                     data("amountCents", 4748L),
                     data("durationMs", 41L)
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
  ): IO[Unit] = {
    val next = new AtomicInteger(0)
    def worker: IO[Unit] = IO(next.getAndIncrement()).flatMap { n =>
      if (n >= count) IO.unit
      else
        IO.monotonic.flatMap { start =>
          request(logger, from + n) *> IO.monotonic
            .map(end => latencies(n) = (end - start).toNanos)
        } *> worker
    }
    List.fill(Concurrency)(worker).parSequence_
  }

  def main(argv: Array[String]): Unit = {
    val args = parseArgs(argv)
    val variant = if (args.logging) "cats" else "cats-baseline"
    val stdout = setupScribe(args.verify)
    val ctx = LogContext.create.unsafeRunSync()
    val logger =
      if (args.logging)
        Some(
          new RequestLogger(Log.named[IO](LoggerName, ctx), ctx, args.verify)
        )
      else None

    args.scenario match {
      case "requests" =>
        serve(logger, 0, WarmupRequests, new Array[Long](WarmupRequests))
          .unsafeRunSync()
        Thread.sleep(2000)
        val latencies = new Array[Long](MeasuredRequests)
        val droppedBefore = stdout.dropped
        val c0 = counters()
        val t0 = System.nanoTime()
        serve(logger, WarmupRequests, MeasuredRequests, latencies)
          .unsafeRunSync()
        val t1 = System.nanoTime()
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
        def loop(i: Int, n: Int): IO[Unit] =
          if (i == n) IO.unit
          else
            logger
              .fold(IO.unit)(_.debug("req-1", s"value $i")) >> loop(i + 1, n)
        def body(n: Int): IO[Unit] =
          logger.fold(loop(0, n))(_.scope("req-1")(loop(0, n)))
        body(DebugWarmup).unsafeRunSync()
        val a0 = allocatedBytes()
        val t0 = System.nanoTime()
        body(DebugCalls).unsafeRunSync()
        val t1 = System.nanoTime()
        val a1 = allocatedBytes()
        stdout.close()
        reportDebugHot(variant, args, t1 - t0, a1 - a0)

      case other => sys.error(s"Unknown scenario: $other")
    }
  }
}
