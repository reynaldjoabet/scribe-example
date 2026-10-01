package app.logging

import zio.*

object ZioLogging {

  /** Replaces ZIO's default console logger with [[ScribeZLogger]]. Only
    * installs the logger: Scribe itself is configured by `live` (or by a test).
    * ZIO itself doesn't filter by level (its default logger does), so every
    * ZIO.log* call reaches ScribeZLogger and Scribe's levels are the single
    * source of truth.
    */
  val scribeLogger: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++ Runtime.addLogger(ScribeZLogger)

  /** Use as `bootstrap` in ZIOAppDefault: configures Scribe, installs
    * `scribeLogger`, flushes logs on shutdown.
    */
  val live: ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.succeed(ScribeLogging.init()))(_ =>
        ZIO.succeed(ScribeLogging.shutdown())
      )
    ) ++
      scribeLogger
}
