package app.logging

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scribe.data
import scribe.handler.{AsynchronousLogHandle, LogHandle, Overflow, SynchronousLogHandle}
import scribe.output.LogOutput
import scribe.output.format.{ASCIIOutputFormat, OutputFormat}
import scribe.writer.{SystemOutWriter, Writer}
import scribe.{Level, LogRecord, Logger}

import java.util.concurrent.atomic.AtomicLong

/** Usage: HandleBenchmark <sync|scribe-async|async-writer>; pipe stdout to /dev/null, results go to stderr. */
object HandleBenchmark {
  private val written = new AtomicLong(0L)

  private def counting(w: Writer): Writer = new Writer {
    override def write(record: LogRecord, output: LogOutput, outputFormat: OutputFormat): Unit = {
      w.write(record, output, outputFormat)
      written.incrementAndGet()
    }
  }

  def main(args: Array[String]): Unit = {
    val mode = args(0)
    val (handle, inner, done): (LogHandle, Writer, () => Unit) = mode match {
      case "sync" => (SynchronousLogHandle, SystemOutWriter, () => ())
      case "scribe-async" =>
        val h = AsynchronousLogHandle(65536, Overflow.DropOld)
        (h, SystemOutWriter, () => { Thread.sleep(2000); h.flush() })
      case "async-writer" =>
        val w = new AsyncStdoutWriter(65536)
        (SynchronousLogHandle, w, () => { w.close(); System.err.println(s"  (dropped counter: ${w.dropped})") })
    }
    Logger.root.clearHandlers().clearModifiers().withMinimumLevel(Level.Info)
      .withHandler(writer = JsonLogFormat.writer(counting(inner)), outputFormat = ASCIIOutputFormat, handle = handle)
      .replace()

    val ctx = LogContext.create.unsafeRunSync()
    val log = Log.named[IO]("bench", ctx)
    val fibers = 8
    def burst(n: Int): IO[Unit] =
      ctx.scoped("requestId" -> "r-1")((1 to fibers).toList.parTraverse_(f =>
        (1 to n / fibers).toList.traverse_(i => log.info("order processed", data("fiber", f), data("i", i)))
      ))

    burst(50000).unsafeRunSync() // warm-up
    Thread.sleep(3000)
    val n = 200000
    val before = written.get()
    val t0 = System.nanoTime()
    burst(n).unsafeRunSync()
    val t1 = System.nanoTime()
    done()
    val t2 = System.nanoTime()
    val w = written.get() - before
    System.err.println(
      f"$mode%-13s callers: ${(t1 - t0) / 1e6}%7.0f ms (${n / ((t1 - t0) / 1e9)}%,10.0f logs/s)   " +
        f"written: $w%,7d / $n%,d   drain after callers done: ${(t2 - t1) / 1e6}%.0f ms"
    )
  }
}
