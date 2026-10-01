package app

import app.logging.ZioLogging
import scribe.handler.LogHandler
import scribe.{Level, LogRecord, Logger}
import zio.*

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** Runs effects with ZIO logging routed to Scribe, capturing every record under
  * `app` at INFO and above.
  */
object LogCapture {
  private val captured = new ConcurrentLinkedQueue[LogRecord]
  Logger("app")
    .orphan()
    .clearHandlers()
    .withMinimumLevel(Level.Info)
    .withHandler(LogHandler(Level.Trace)(r => captured.add(r)))
    .replace()

  private val runtime: Runtime[Any] =
    Unsafe.unsafe(implicit u =>
      Runtime.unsafe.fromLayer(ZioLogging.scribeLogger)
    )

  /** Clears captured records, runs the effect, returns its Exit. */
  def run[E, A](effect: ZIO[Any, E, A]): Exit[E, A] = {
    captured.clear()
    Unsafe.unsafe(implicit u => runtime.unsafe.run(effect))
  }

  def records: List[LogRecord] = captured.asScala.toList

  def record(message: String): LogRecord =
    records
      .find(_.messages.head.logOutput.plainText == message)
      .getOrElse(
        throw new NoSuchElementException(
          s"No '$message' log in: ${records.map(_.logOutput.plainText)}"
        )
      )

  // Braces, not indentation: the build uses -no-indent, and scalafmt splits the one-line form onto two lines
  extension (r: LogRecord) {
    def field(key: String): Option[Any] = r.data.get(key).map(_())
  }
}
