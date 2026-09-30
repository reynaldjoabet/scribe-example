package app.http

import app.domain.*
import app.payments.PaymentService
import io.circe.Encoder
import io.circe.syntax.*
import zio.*
import zio.http.*

object PaymentRoutes {
  def apply(payments: PaymentService): Routes[Any, Response] = Routes(
    Method.POST / "orders" / string("id") / "charge" -> handler { (id: String, _: Request) =>
      val orderId = OrderId(id)
      payments.charge(orderId).fold(
        error => json(errorStatus(error), ErrorResponse.from(error)),
        result => json(resultStatus(result), ChargeResponse.from(orderId, result))
      )
    }
  )

  private def resultStatus(result: PaymentResult): Status = result match {
    case _: PaymentResult.Approved => Status.Ok
    case _: PaymentResult.Declined => Status.PaymentRequired
  }

  private def errorStatus(error: PaymentError): Status = error match {
    case _: PaymentError.OrderNotFound => Status.NotFound
    case _: PaymentError.AlreadyPaid => Status.Conflict
    case _: PaymentError.InvalidAmount => Status.UnprocessableEntity
    case _: PaymentError.GatewayUnavailable => Status.ServiceUnavailable
  }

  private def json[A: Encoder](status: Status, body: A): Response =
    Response.json(body.asJson.noSpaces).status(status)
}
