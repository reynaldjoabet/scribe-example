package app.logging

import zio.*

object ZioLogging {

  /** ZIO's default console logger replaced by Scribe. ZIO itself doesn't filter
    * by level (its default logger does), so every ZIO.log* call reaches
    * ScribeZLogger and Scribe's levels are the single source of truth.
    */
  val loggers: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++ Runtime.addLogger(ScribeZLogger)

  /** Use as `bootstrap` in ZIOAppDefault: configures Scribe, installs the
    * backend, flushes logs on shutdown.
    */
  val live: ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.succeed(LoggingSetup.init()))(_ =>
        ZIO.succeed(LoggingSetup.shutdown())
      )
    ) ++
      loggers
}
