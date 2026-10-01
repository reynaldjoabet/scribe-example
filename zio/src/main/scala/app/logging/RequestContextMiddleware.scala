package app.logging

import zio.*
import zio.http.*

/** zio-http middleware: annotates every log inside a request with a request id
  * (from X-Request-ID or freshly generated), method and path, and echoes the id
  * back in the response.
  */
object RequestContextMiddleware {
  def apply[R](routes: Routes[R, Response]): Routes[R, Response] =
    routes.transform[R] { next =>
      // Handler.scoped: `next(request)` needs the per-request Scope the server provides (same pattern as zio-http's @@)
      Handler.scoped[R] {
        handler { (request: Request) =>
          for {
            requestId <- ZIO
              .succeed(request.rawHeader("X-Request-ID"))
              .someOrElseZIO(Random.nextUUID.map(_.toString))
            response <- ZIO.logAnnotate(
              LogAnnotation("requestId", requestId),
              LogAnnotation("method", request.method.name),
              LogAnnotation("path", request.path.encode)
            )(next(request))
          } yield response.addHeader("X-Request-ID", requestId)
        }
      }
    }
}
