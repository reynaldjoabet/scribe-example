package app.logging

import scribe.format.Formatter
import scribe.output.format.ASCIIOutputFormat
import scribe.{Level, Logger}

import java.util.concurrent.atomic.AtomicBoolean

/** Configures Scribe for production. Effect-agnostic: each app wraps
  * `init`/`shutdown` in its effect system's lifecycle (a cats-effect Resource,
  * a ZIO bootstrap layer) so queued logs are flushed when the app stops.
  */
object LoggingSetup {
  private lazy val stdout = new AsyncStdoutWriter(capacity = 65536)
  private val initialized = new AtomicBoolean(false)

  /** Lines dropped because stdout couldn't keep up (INFO and below first);
    * export as a metric.
    */
  def dropped: Long = stdout.dropped

  def init(): Unit = if (initialized.compareAndSet(false, true)) {
    val level =
      sys.env.get("LOG_LEVEL").flatMap(Level.get).getOrElse(Level.Info)
    val json = !sys.env.get("LOG_FORMAT").contains("text")

    Logger.root
      .clearHandlers()
      .clearModifiers()
      .withMinimumLevel(level)
      .withHandler(
        // The formatter renders each record (a JSON line, or text for local dev); the writer only moves lines to stdout
        formatter = if (json) JsonLogFormat.formatter else Formatter.strict,
        writer = stdout,
        outputFormat =
          ASCIIOutputFormat // never emit ANSI escapes, even if TERM is set in the container
      )
      .replace(): Unit

    sys.env
      .get("SERVICE_NAME")
      .foreach(s => Logger.root.set("service", s).replace())

    Logger.minimumLevels(
      "org.http4s" -> Level.Warn,
      "zio.http" -> Level.Warn,
      "io.netty" -> Level.Warn,
      "com.zaxxer.hikari" -> Level.Warn
    )

    Logger.system.installJUL()

    // Backstop for exits that skip the effect system's finalizers (e.g. System.exit from a library)
    sys.addShutdownHook(stdout.close()): Unit
  }

  /** Drains and flushes queued logs. */
  def shutdown(): Unit = stdout.close()
}
