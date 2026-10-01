package app.logging

import cats.effect.{IO, IOLocal, LiftIO, MonadCancelThrow}

/** Fiber-local structured context (request id, user id, trace id, ...).
  *
  * Replaces Scribe's thread-local MDC, which is unreliable under cats-effect
  * because fibers hop between threads. IOLocal is copied on fork, so child
  * fibers inherit the context and their changes never leak back to the parent.
  */
final class LogContext private (local: IOLocal[LogContext.Entries]) {
  def get[F[_]: LiftIO]: F[Map[String, String]] =
    LiftIO[F].liftIO(local.get.map(_.values))

  /** For Log: the context together with its LogRecord form. */
  private[logging] def entries[F[_]: LiftIO]: F[LogContext.Entries] =
    LiftIO[F].liftIO(local.get)

  /** Adds `kv` to the context for the duration of `fa`, restoring the previous
    * context afterwards (even on error).
    */
  def scoped[F[_]: LiftIO: MonadCancelThrow, A](
      kv: (String, String)*
  )(fa: F[A]): F[A] =
    MonadCancelThrow[F].bracket(
      LiftIO[F].liftIO(
        local.modify(prev => (new LogContext.Entries(prev.values ++ kv), prev))
      )
    )(_ => fa)(prev => LiftIO[F].liftIO(local.set(prev)))
}

object LogContext {
  val create: IO[LogContext] =
    IOLocal(Entries.empty).map(new LogContext(_))

  /** The context values, plus the same values as LogRecord data. That form is
    * built once per scope, on its first log, instead of on every log call.
    */
  private[logging] final class Entries(val values: Map[String, String]) {
    lazy val data: Map[String, () => Any] = values.transform((_, v) => () => v)
  }

  private object Entries {
    val empty: Entries = new Entries(Map.empty)
  }
}
