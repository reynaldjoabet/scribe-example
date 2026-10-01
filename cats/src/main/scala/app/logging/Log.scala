package app.logging

import cats.effect.{LiftIO, Sync}
import cats.syntax.all.*
import scribe.mdc.MDC
import scribe.message.LoggableMessage
import scribe.{
  Level,
  LogFeature,
  LogRecord,
  Logger,
  LoggerId,
  LoggerSupport,
  Scribe
}
import sourcecode.{FileName, Line, Name, Pkg}

/** Effectful Scribe logger for cats-effect.
  *
  * Differences from `scribe.cats.io` / `logger.f[F]`:
  *   - Disabled levels return `F.unit` without allocating a LogRecord or
  *     evaluating the message (cached level check).
  *   - Context comes from a fiber-local [[LogContext]] instead of the
  *     thread-local MDC (which, on a compute thread, belongs to whichever fiber
  *     ran there last). Scribe's global MDC isn't used either: put static
  *     fields on a Logger instead (`Logger.root.set("service", ...)`).
  *   - The Logger is looked up by id on each call, so runtime level changes
  *     (`Logger(...).replace()`) take effect.
  *
  * Keeps the full Scribe API (`info`, `error(msg, throwable, data(...))`, ...)
  * with compile-time source positions.
  */
final class Log[F[_]] private (
    id: LoggerId,
    loggerName: String,
    ctx: LogContext
)(implicit
    F: Sync[F],
    L: LiftIO[F]
) extends Scribe[F] {
  private def logger: Logger = Logger(id)
  // Set on every record: without it Scribe searches all logger names for this one on every log line
  private val someLoggerName = Some(loggerName)

  override def log(record: => LogRecord): F[Unit] = F.delay(logger.log(record))

  // The level check runs when the effect is built, so a disabled log costs one cached map lookup. An effect value
  // built once and re-run (e.g. `Stream.repeatEval(log.debug(...))`) keeps the level decision it was built with.
  override def log(level: Level, mdc: MDC, features: LogFeature*)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line
  ): F[Unit] = {
    val l = logger
    if (!l.includes(level)) F.unit
    else
      ctx.entries[F].flatMap { context =>
        F.delay(
          l.log(
            record(level, context.data, features, pkg, fileName, name, line)
          )
        )
      }
  }

  // Scribe's `debug("text")` overloads turn the text into a LogFeature before calling `log`, which would allocate
  // even when the level is disabled; these check the level first. Each is kept small (no shared helper) so the JIT
  // inlines it into the call site.
  override def trace(message: => String)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line,
      mdc: MDC
  ): F[Unit] =
    if (!logger.includes(Level.Trace)) F.unit
    else log(Level.Trace, mdc, LogFeature.string2LoggableMessage(message))
  override def debug(message: => String)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line,
      mdc: MDC
  ): F[Unit] =
    if (!logger.includes(Level.Debug)) F.unit
    else log(Level.Debug, mdc, LogFeature.string2LoggableMessage(message))
  override def info(message: => String)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line,
      mdc: MDC
  ): F[Unit] =
    if (!logger.includes(Level.Info)) F.unit
    else log(Level.Info, mdc, LogFeature.string2LoggableMessage(message))
  override def warn(message: => String)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line,
      mdc: MDC
  ): F[Unit] =
    if (!logger.includes(Level.Warn)) F.unit
    else log(Level.Warn, mdc, LogFeature.string2LoggableMessage(message))
  override def error(message: => String)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line,
      mdc: MDC
  ): F[Unit] =
    if (!logger.includes(Level.Error)) F.unit
    else log(Level.Error, mdc, LogFeature.string2LoggableMessage(message))
  override def fatal(message: => String)(implicit
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line,
      mdc: MDC
  ): F[Unit] =
    if (!logger.includes(Level.Fatal)) F.unit
    else log(Level.Fatal, mdc, LogFeature.string2LoggableMessage(message))

  /** Builds the record in one go: messages and context go straight in, and only
    * the remaining features (`data(...)`, ...) are applied one by one, each of
    * which copies the record.
    */
  private def record(
      level: Level,
      context: Map[String, () => Any],
      features: Seq[LogFeature],
      pkg: Pkg,
      fileName: FileName,
      name: Name,
      line: Line
  ): LogRecord = {
    var messages = List.empty[LoggableMessage]
    var others = List.empty[LogFeature]
    features.reverseIterator.foreach {
      case m: LoggableMessage => messages = m :: messages
      case f                  => others = f :: others
    }
    val (fn, className) = LoggerSupport.className(pkg, fileName)
    val r = LogRecord(
      level = level,
      levelValue = level.value,
      messages = messages,
      fileName = fn,
      className = className,
      methodName = name.value match {
        case "anonymous" | "" => None
        case v                => Some(v)
      },
      line = Some(line.value),
      column = None,
      data =
        context, // per-call data(...) is added on top, so it wins over the context
      loggerName = someLoggerName
    )
    others.foldLeft(r)((record, feature) => feature(record))
  }
}

object Log {
  def named[F[_]: Sync: LiftIO](name: String, ctx: LogContext): Log[F] =
    new Log[F](
      Logger(name).id,
      name.replace("$", ""),
      ctx
    ) // Scribe drops "$" from logger names

  def forClass[F[_]: Sync: LiftIO](cls: Class[?], ctx: LogContext): Log[F] =
    named[F](cls.getName, ctx)
}
