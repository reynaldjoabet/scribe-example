package app.payments

import app.domain.*
import zio.*

import java.net.SocketTimeoutException

trait PaymentGateway {
  /** `idempotencyKey` lets the provider dedupe retries of the same charge. */
  def charge(card: CardToken, amount: Money, idempotencyKey: OrderId): Task[PaymentResult]
}

/**
 * Stand-in for a real provider (Stripe, Adyen, ...). Behaviour is driven by the card's last 4 digits, like provider
 * test cards: 0002 insufficient funds, 0069 expired, 0119 network failure; amounts over $10,000 are flagged as fraud.
 */
final class FakePaymentGateway extends PaymentGateway {
  private val FraudThresholdCents = 1000000L

  def charge(card: CardToken, amount: Money, idempotencyKey: OrderId): Task[PaymentResult] =
    ZIO.logAnnotate(LogAnnotation("card", card.toString), LogAnnotation("idempotencyKey", idempotencyKey.value)) {
      ZIO.logDebug("calling payment provider")
    } *>
      ZIO.sleep(40.millis) *> // simulated network latency
      (card.last4 match {
        case "0002" => ZIO.succeed(PaymentResult.Declined(DeclineReason.InsufficientFunds))
        case "0069" => ZIO.succeed(PaymentResult.Declined(DeclineReason.CardExpired))
        case "0119" => ZIO.fail(new SocketTimeoutException("Read timed out from api.provider.example"))
        case _ if amount.cents > FraudThresholdCents => ZIO.succeed(PaymentResult.Declined(DeclineReason.FraudSuspected))
        case _ => Random.nextUUID.map(uuid => PaymentResult.Approved(TransactionId(s"tx-$uuid")))
      })
}

object FakePaymentGateway {
  val live: ULayer[PaymentGateway] = ZLayer.succeed(new FakePaymentGateway)
}
