package app.http

import app.domain.*
import app.payments.PaymentService
import cats.effect.Concurrent
import cats.syntax.all.*
import org.http4s.{HttpRoutes, Method, Uri}
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.dsl.Http4sDsl

final class PaymentRoutes[F[_]: Concurrent](payments: PaymentService[F]) extends Http4sDsl[F] {
  // http4s types don't provide CanEqual; the dsl's extractors need it under -language:strictEquality
  private given CanEqual[Method, Method] = CanEqual.derived
  private given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived

  val routes: HttpRoutes[F] = HttpRoutes.of[F] { case POST -> Root / "orders" / id / "charge" =>
    val orderId = OrderId(id)
    payments
      .charge(orderId)
      .flatMap {
        case r: PaymentResult.Approved => Ok(ChargeResponse.from(orderId, r))
        case r: PaymentResult.Declined => PaymentRequired(ChargeResponse.from(orderId, r))
      }
      .recoverWith {
        case e: PaymentError.OrderNotFound => NotFound(ErrorResponse.from(e))
        case e: PaymentError.AlreadyPaid => Conflict(ErrorResponse.from(e))
        case e: PaymentError.InvalidAmount => UnprocessableEntity(ErrorResponse.from(e))
        case e: PaymentError.GatewayUnavailable => ServiceUnavailable(ErrorResponse.from(e))
      }
  }
}
