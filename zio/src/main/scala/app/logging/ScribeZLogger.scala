package app.logging

import scribe.{Level, LogFeature, LogRecord, Logger}
import zio.*

/** ZIO logging backend that sends every `ZIO.log*` call (yours and ZIO
  * libraries', e.g. zio-http) to Scribe.
  *
  * ZIO already provides the fiber-aware frontend: `ZIO.logInfo`,
  * `ZIO.logAnnotate` (fiber-local context, the ZIO equivalent of the cats app's
  * LogContext) and `ZIO.logSpan`. This only maps ZIO's log call to a Scribe
  * LogRecord:
  *   - level filtering is Scribe's (cached per logger), checked before the
  *     message is evaluated
  *   - the source location from ZIO's `Trace` becomes the logger name, so
  *     per-package levels work
  *   - annotations and span durations become record data; a failure's Throwable
  *     becomes the stack trace
  */
object ScribeZLogger extends ZLogger[String, Unit] {
  override def apply(
      trace: Trace,
      fiberId: FiberId,
      logLevel: LogLevel,
      message: () => String,
      cause: Cause[Any],
      context: FiberRefs,
      spans: List[LogSpan],
      annotations: Map[String, String]
  ): Unit = {
    val level = toScribe(logLevel)
    val (className, methodName, fileName, line) = location(trace)
    val logger = Logger(className)
    if (logger.includes(level)) {
      val now = java.lang.System.currentTimeMillis()
      val data: Map[String, () => Any] =
        annotations.map((k, v) => k -> (() => v)) ++
          spans.map(s => s"span.${s.label}" -> (() => now - s.startTime))
      val features: List[LogFeature] =
        LogFeature.string2LoggableMessage(message()) :: throwable(cause)
          .map(LogFeature.throwable2LoggableMessage(_))
          .toList
      val record = LogRecord(
        level = level,
        levelValue = level.value,
        messages = Nil,
        fileName = fileName,
        className = className,
        methodName = methodName,
        line = line,
        column = None,
        data = data
      ).withFeatures(features*)
      logger.log(record)
    }
  }

  // zio.LogLevel doesn't provide CanEqual; needed to match on it under -language:strictEquality
  private given CanEqual[LogLevel, LogLevel] = CanEqual.derived

  private def toScribe(level: LogLevel): Level = level match {
    case LogLevel.Fatal   => Level.Fatal
    case LogLevel.Error   => Level.Error
    case LogLevel.Warning => Level.Warn
    case LogLevel.Info    => Level.Info
    case LogLevel.Debug   => Level.Debug
    case _                => Level.Trace
  }

  /** ZIO's Trace carries "pkg.Class.method" plus file and line, captured at
    * compile time (no stack walking).
    */
  private def location(
      trace: Trace
  ): (String, Option[String], String, Option[Int]) = trace match {
    case Trace(loc, file, line) =>
      val dot = loc.lastIndexOf('.')
      if (dot > 0)
        (loc.substring(0, dot), Some(loc.substring(dot + 1)), file, Some(line))
      else (loc, None, file, Some(line))
    case _ => ("zio", None, "", None)
  }

  private def throwable(cause: Cause[Any]): Option[Throwable] =
    if (cause.isEmpty) None
    else
      cause.failureOption
        .collect { case t: Throwable => t }
        .orElse(cause.dieOption)
        .orElse(Some(cause.squashWith {
          case t: Throwable => t
          case other        => new RuntimeException(String.valueOf(other))
        }))
}
