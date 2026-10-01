package app.logging

import scribe.message.LoggableMessage
import scribe.throwable.TraceLoggableMessage
import scribe.{Level, LogRecord, Logger, LoggerId}
import zio.*

import java.util.concurrent.ConcurrentHashMap

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
    val site = callSite(trace)
    val logger = Logger(site.loggerId)
    if (logger.includes(level)) {
      val text = LoggableMessage.string2LoggableMessage(message())
      val t = throwable(cause)
      logger.log(
        LogRecord(
          level = level,
          levelValue = level.value,
          messages =
            if (t.isEmpty) text :: Nil
            else text :: TraceLoggableMessage(t.get) :: Nil,
          fileName = site.fileName,
          className = site.className,
          methodName = site.methodName,
          line = site.line,
          column = None,
          data = data(annotations, spans),
          loggerName = site.loggerName
        )
      )
    }
  }

  private def data(
      annotations: Map[String, String],
      spans: List[LogSpan]
  ): Map[String, () => Any] = {
    val fields: Map[String, () => Any] =
      annotations.transform((_, v) => () => v)
    if (spans.isEmpty) fields
    else {
      val now = java.lang.System.currentTimeMillis()
      fields ++ spans.map(s => s"span.${s.label}" -> (() => now - s.startTime))
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

  /** A log call site, parsed from ZIO's Trace
    * ("pkg.Class.method(File.scala:42)", a string constant generated at compile
    * time).
    */
  private final class CallSite(
      val className: String,
      val methodName: Option[String],
      val fileName: String,
      val line: Option[Int]
  ) {
    val loggerId: LoggerId = Logger(className).id
    // Scribe searches all logger names on every log line when a record has none; give it the one Logger(...) stored
    val loggerName: Option[String] = Some(className.replace("$", ""))
  }

  // Parsing a Trace allocates ~8 objects, which every log call (enabled or not) used to pay. There's one Trace per
  // call site in the code, so caching them is bounded by the code size; the cap only guards against code that builds
  // traces at runtime.
  private val callSites = new ConcurrentHashMap[Trace, CallSite]
  private val MaxCallSites = 10000

  private def callSite(trace: Trace): CallSite = {
    val cached = callSites.get(trace)
    if (cached ne null) cached
    else {
      val site = parse(trace)
      if (callSites.size < MaxCallSites)
        callSites.putIfAbsent(trace, site): Unit
      site
    }
  }

  private def parse(trace: Trace): CallSite = trace match {
    case Trace(location, file, line) =>
      val dot = location.lastIndexOf('.')
      if (dot > 0)
        new CallSite(
          location.substring(0, dot),
          Some(location.substring(dot + 1)),
          file,
          Some(line)
        )
      else new CallSite(location, None, file, Some(line))
    case _ => new CallSite("zio", None, "", None)
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
