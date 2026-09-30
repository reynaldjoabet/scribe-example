package app.domain

import scala.util.control.NoStackTrace

enum DeclineReason {
  case InsufficientFunds, CardExpired, FraudSuspected
}

/** Outcome of a charge the gateway processed. A decline is a normal business
  * result, not an error.
  */
enum PaymentResult {
  case Approved(transactionId: TransactionId)
  case Declined(reason: DeclineReason)
}

/** Failures that stop a charge from being processed.
  *
  * NoStackTrace: these are expected outcomes, so capturing a stack trace would
  * be wasted work and noise in the logs. A wrapped `cause` (e.g. the network
  * exception behind GatewayUnavailable) keeps its own trace.
  */
sealed abstract class PaymentError(message: String, cause: Throwable = null)
    extends Exception(message, cause)
    with NoStackTrace

object PaymentError {
  final case class OrderNotFound(orderId: OrderId)
      extends PaymentError(s"Order ${orderId.value} not found")

  final case class AlreadyPaid(orderId: OrderId)
      extends PaymentError(s"Order ${orderId.value} is already paid")

  final case class InvalidAmount(orderId: OrderId, amount: Money)
      extends PaymentError(
        s"Order ${orderId.value} has non-positive amount ${amount.cents}"
      )

  final case class GatewayUnavailable(reason: String, underlying: Throwable)
      extends PaymentError(s"Payment gateway unavailable: $reason", underlying)
}
