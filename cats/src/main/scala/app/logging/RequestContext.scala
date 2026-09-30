package app.logging

import cats.data.{Kleisli, OptionT}
import cats.effect.{LiftIO, Sync}
import cats.syntax.all.*
import org.http4s.HttpRoutes
import org.typelevel.ci.*

import java.util.UUID

/** http4s middleware: puts a request id (from X-Request-ID or freshly
  * generated) into the log context.
  */
object RequestContext {
  def apply[F[_]: Sync: LiftIO](
      ctx: LogContext
  )(routes: HttpRoutes[F]): HttpRoutes[F] = Kleisli { req =>
    val incoming = req.headers.get(ci"X-Request-ID").map(_.head.value)
    OptionT(
      incoming
        .fold(Sync[F].delay(UUID.randomUUID().toString))(_.pure[F])
        .flatMap { requestId =>
          ctx.scoped(
            "requestId" -> requestId,
            "method" -> req.method.name,
            "path" -> req.pathInfo.renderString
          )(
            routes(req).map(_.putHeaders("X-Request-ID" -> requestId)).value
          )
        }
    )
  }
}
