package app.logging

import cats.effect.{IO, IOLocal, LiftIO, MonadCancelThrow}

/** Fiber-local structured context (request id, user id, trace id, ...).
  *
  * Replaces Scribe's thread-local MDC, which is unreliable under cats-effect
  * because fibers hop between threads. IOLocal is copied on fork, so child
  * fibers inherit the context and their changes never leak back to the parent.
  */
final class LogContext private (local: IOLocal[Map[String, String]]) {
  def get[F[_]: LiftIO]: F[Map[String, String]] = LiftIO[F].liftIO(local.get)

  /** Adds `kv` to the context for the duration of `fa`, restoring the previous
    * context afterwards (even on error).
    */
  def scoped[F[_]: LiftIO: MonadCancelThrow, A](
      kv: (String, String)*
  )(fa: F[A]): F[A] =
    MonadCancelThrow[F].bracket(
      LiftIO[F].liftIO(local.modify(prev => (prev ++ kv, prev)))
    )(_ => fa)(prev => LiftIO[F].liftIO(local.set(prev)))
}

object LogContext {
  val create: IO[LogContext] =
    IOLocal(Map.empty[String, String]).map(new LogContext(_))
}
