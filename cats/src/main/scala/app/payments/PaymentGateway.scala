package app.payments

import app.domain.*
import app.logging.Log
import cats.effect.Async
import cats.syntax.all.*
import scribe.data

import java.net.SocketTimeoutException
import java.util.UUID
import scala.concurrent.duration.*

trait PaymentGateway[F[_]] {
  /** `idempotencyKey` lets the provider dedupe retries of the same charge. */
  def charge(card: CardToken, amount: Money, idempotencyKey: OrderId): F[PaymentResult]
}

/**
 * Stand-in for a real provider (Stripe, Adyen, ...). Behaviour is driven by the card's last 4 digits, like provider
 * test cards: 0002 insufficient funds, 0069 expired, 0119 network failure; amounts over $10,000 are flagged as fraud.
 */
final class FakePaymentGateway[F[_]: Async](log: Log[F]) extends PaymentGateway[F] {
  private val FraudThresholdCents = 1000000L

  def charge(card: CardToken, amount: Money, idempotencyKey: OrderId): F[PaymentResult] =
    log.debug("calling payment provider", data("card", card.toString), data("idempotencyKey", idempotencyKey.value)) *>
      Async[F].sleep(40.millis) *> // simulated network latency
      (card.last4 match {
        case "0002" => PaymentResult.Declined(DeclineReason.InsufficientFunds).pure[F]
        case "0069" => PaymentResult.Declined(DeclineReason.CardExpired).pure[F]
        case "0119" => Async[F].raiseError(new SocketTimeoutException("Read timed out from api.provider.example"))
        case _ if amount.cents > FraudThresholdCents => PaymentResult.Declined(DeclineReason.FraudSuspected).pure[F]
        case _ => Async[F].delay(PaymentResult.Approved(TransactionId(s"tx-${UUID.randomUUID()}")))
      })
}
