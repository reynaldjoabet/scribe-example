package app.logging

import cats.effect.{Resource, Sync}

object CatsLogging {

  /** Acquire first in `IOApp.run`; release drains and flushes queued logs on
    * shutdown (incl. SIGTERM).
    */
  def resource[F[_]](implicit F: Sync[F]): Resource[F, Unit] =
    Resource.make(F.delay(ScribeLogging.init()))(_ =>
      F.delay(ScribeLogging.shutdown())
    )
}
