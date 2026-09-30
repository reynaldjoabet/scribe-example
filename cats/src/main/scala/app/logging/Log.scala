package app.logging

import cats.effect.{LiftIO, Sync}
import cats.syntax.all.*
import scribe.mdc.MDC
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
  *     thread-local MDC.
  *   - The Logger is looked up by id on each call, so runtime level changes
  *     (`Logger(...).replace()`) take effect.
  *
  * Keeps the full Scribe API (`info`, `error(msg, throwable, data(...))`, ...)
  * with compile-time source positions.
  */
final class Log[F[_]] private (id: LoggerId, ctx: LogContext)(implicit
    F: Sync[F],
    L: LiftIO[F]
) extends Scribe[F] {
  private def logger: Logger = Logger(id)

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
      ctx.get[F].flatMap { context =>
        F.delay {
          // MDC.global only: the thread-local MDC of a compute thread is meaningless under cats-effect
          val record =
            LoggerSupport(level, Nil, pkg, fileName, name, line, MDC.global)
              .withFeatures(features*)
          l.log(
            if (context.isEmpty) record
            else
              record.copy(data =
                context.map((k, v) => k -> (() => v)) ++ record.data
              )
          )
        }
      }
  }
}

object Log {
  def named[F[_]: Sync: LiftIO](name: String, ctx: LogContext): Log[F] =
    new Log[F](Logger(name).id, ctx)

  def forClass[F[_]: Sync: LiftIO](cls: Class[?], ctx: LogContext): Log[F] =
    named[F](cls.getName, ctx)
}
