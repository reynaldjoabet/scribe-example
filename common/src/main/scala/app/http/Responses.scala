package app.http

import app.domain.*
import io.circe.Codec

/** HTTP response bodies, shared so the cats and ZIO apps return identical JSON.
  */
final case class ChargeResponse(
    orderId: OrderId,
    status: String,
    transactionId: Option[TransactionId],
    declineReason: Option[String]
) derives Codec.AsObject

object ChargeResponse {
  def from(orderId: OrderId, result: PaymentResult): ChargeResponse =
    result match {
      case PaymentResult.Approved(tx) =>
        ChargeResponse(orderId, "approved", Some(tx), None)
      case PaymentResult.Declined(reason) =>
        ChargeResponse(orderId, "declined", None, Some(reason.toString))
    }
}

final case class ErrorResponse(error: String, message: String)
    derives Codec.AsObject

object ErrorResponse {
  // GatewayUnavailable hides provider details from clients; the full cause is in the error log line
  def from(error: PaymentError): ErrorResponse = error match {
    case e: PaymentError.OrderNotFound =>
      ErrorResponse("order_not_found", e.getMessage)
    case e: PaymentError.AlreadyPaid =>
      ErrorResponse("already_paid", e.getMessage)
    case e: PaymentError.InvalidAmount =>
      ErrorResponse("invalid_amount", e.getMessage)
    case _: PaymentError.GatewayUnavailable =>
      ErrorResponse(
        "payment_unavailable",
        "Payment provider unavailable, retry later"
      )
  }
}
