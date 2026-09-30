package app.logging

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scribe.data
import scribe.format.Formatter
import scribe.handler.{
  AsynchronousLogHandle,
  LogHandle,
  Overflow,
  SynchronousLogHandle
}
import scribe.output.{EmptyOutput, LogOutput}
import scribe.output.format.{ASCIIOutputFormat, OutputFormat}
import scribe.writer.{SystemOutWriter, Writer}
import scribe.{Level, LogRecord, Logger}

import java.util.concurrent.atomic.LongAdder

/** Usage: HandleBenchmark <mode> [records]. Send stdout to /dev/null (or a slow
  * reader); results go to stderr.
  *
  * Modes (in a code block so scalafmt doesn't reflow the list):
  * {{{
  * Stage modes, each adding one cost (nothing written to stdout):
  *   front             Log -> LogContext -> Scribe routing only; no formatting
  *   text-format       + Formatter.strict (Scribe runs it even in JSON mode unless replaced)
  *   circe             + JSON via Scribe's circe support (CirceJsonLogFormat)
  *   direct            + JSON via JsonLogFormat (StringBuilder)
  *   jsoniter          + JSON via JsoniterJsonLogFormat
  * Full pipelines to stdout:
  *   sync              Formatter.strict + circe JSON + Scribe's SystemOutWriter, synchronous
  *   scribe-async      same, through Scribe's AsynchronousLogHandle
  *   current           Formatter.strict + circe JSON + AsyncStdoutWriter (LoggingSetup before the refinements)
  *   sync-direct       JsonLogFormat.formatter + SystemOutWriter
  *   refined           JsonLogFormat.formatter + AsyncStdoutWriter (LoggingSetup now)
  *   refined-jsoniter  JsoniterJsonLogFormat.formatter + AsyncStdoutWriter
  * }}}
  */
object HandleBenchmark {
  private val written = new LongAdder
  private val sink = new LongAdder

  private val noFormatter: Formatter = (_: LogRecord) => EmptyOutput

  private object Discard extends Writer {
    override def write(
        record: LogRecord,
        output: LogOutput,
        outputFormat: OutputFormat
    ): Unit =
      sink.add(
        output.plainText.length.toLong
      ) // consume the output so formatting can't be optimised away
  }

  private def counting(w: Writer): Writer = new Writer {
    override def write(
        record: LogRecord,
        output: LogOutput,
        outputFormat: OutputFormat
    ): Unit = {
      w.write(record, output, outputFormat)
      written.increment()
    }
  }

  private final case class Setup(
      formatter: Formatter,
      writer: Writer,
      handle: LogHandle,
      done: () => Unit
  )

  def main(args: Array[String]): Unit = {
    val mode = args(0)
    val n = args.lift(1).map(_.toInt).getOrElse(400000)
    lazy val asyncWriter = new AsyncStdoutWriter(65536)
    lazy val scribeAsync = AsynchronousLogHandle(65536, Overflow.DropOld)
    var droppedBefore = 0L
    val closeAsync = () => {
      asyncWriter.close()
      System.err.println(
        s"  dropped during measured burst: ${asyncWriter.dropped - droppedBefore}"
      )
    }
    val setup = mode match {
      case "front" =>
        Setup(noFormatter, Discard, SynchronousLogHandle, () => ())
      case "text-format" =>
        Setup(Formatter.strict, Discard, SynchronousLogHandle, () => ())
      case "circe" =>
        Setup(
          noFormatter,
          CirceJsonLogFormat.writer(Discard),
          SynchronousLogHandle,
          () => ()
        )
      case "direct" =>
        Setup(
          JsonLogFormat.formatter,
          Discard,
          SynchronousLogHandle,
          () => ()
        )
      case "jsoniter" =>
        Setup(
          JsoniterJsonLogFormat.formatter,
          Discard,
          SynchronousLogHandle,
          () => ()
        )
      case "sync" =>
        Setup(
          Formatter.strict,
          CirceJsonLogFormat.writer(SystemOutWriter),
          SynchronousLogHandle,
          () => ()
        )
      case "scribe-async" =>
        Setup(
          Formatter.strict,
          CirceJsonLogFormat.writer(SystemOutWriter),
          scribeAsync,
          () => {
            Thread.sleep(2000)
            scribeAsync.flush()
          }
        )
      case "current" =>
        Setup(
          Formatter.strict,
          CirceJsonLogFormat.writer(asyncWriter),
          SynchronousLogHandle,
          closeAsync
        )
      case "sync-direct" =>
        Setup(
          JsonLogFormat.formatter,
          SystemOutWriter,
          SynchronousLogHandle,
          () => ()
        )
      case "refined" =>
        Setup(
          JsonLogFormat.formatter,
          asyncWriter,
          SynchronousLogHandle,
          closeAsync
        )
      case "refined-jsoniter" =>
        Setup(
          JsoniterJsonLogFormat.formatter,
          asyncWriter,
          SynchronousLogHandle,
          closeAsync
        )
      case other => sys.error(s"Unknown mode: $other")
    }
    Logger.root
      .clearHandlers()
      .clearModifiers()
      .withMinimumLevel(Level.Info)
      .withHandler(
        formatter = setup.formatter,
        writer = counting(setup.writer),
        outputFormat = ASCIIOutputFormat,
        handle = setup.handle
      )
      .replace(): Unit

    val ctx = LogContext.create.unsafeRunSync()
    val log = Log.named[IO]("bench", ctx)
    val fibers = 8
    def burst(count: Int): IO[Unit] =
      ctx.scoped("requestId" -> "r-1")(
        (1 to fibers).toList.parTraverse_(f =>
          (1 to count / fibers).toList.traverse_ { i =>
            // 1% errors, so a slow-stdout run shows whether errors survive when lines must be dropped
            if (i % 100 == 0)
              log.error("payment failed", data("fiber", f), data("i", i))
            else log.info("order processed", data("fiber", f), data("i", i))
          }
        )
      )

    burst(100000).unsafeRunSync() // warm-up (JIT)
    Thread.sleep(3000) // let async writers drain the warm-up
    val before = written.sum()
    if (mode.startsWith("refined") || mode == "current")
      droppedBefore = asyncWriter.dropped
    val t0 = System.nanoTime()
    burst(n).unsafeRunSync()
    val t1 = System.nanoTime()
    setup.done()
    val t2 = System.nanoTime()
    val w = written.sum() - before
    val seconds = (t1 - t0) / 1e9
    System.err.println(
      f"$mode%-12s callers: ${(t1 - t0) / 1e6}%6.0f ms  ${n / seconds}%,10.0f logs/s  " +
        f"${(t1 - t0).toDouble / n * fibers}%,6.0f ns/log/fiber   written: $w%,7d / $n%,d   drain: ${(t2 - t1) / 1e6}%.0f ms"
    )
  }
}
