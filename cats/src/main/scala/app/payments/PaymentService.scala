package app.payments

import app.domain.*
import app.logging.Log
import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import scribe.{LogFeature, data}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

final class PaymentService[F[_]: Async](
    orders: OrderRepository[F],
    gateway: PaymentGateway[F],
    log: Log[F],
    gatewayTimeout: FiniteDuration = 5.seconds
) {
  def charge(id: OrderId): F[PaymentResult] =
    orders.find(id).flatMap {
      case None =>
        log.warn("order not found", data("orderId", id.value)) *> PaymentError
          .OrderNotFound(id)
          .raiseError
      case Some(order) if order.status.isInstanceOf[OrderStatus.Paid] =>
        log.warn("order already paid", orderFields(order)) *> PaymentError
          .AlreadyPaid(id)
          .raiseError
      case Some(order) if !order.total.isPositive =>
        log.warn("order has invalid amount", orderFields(order)) *> PaymentError
          .InvalidAmount(id, order.total)
          .raiseError
      case Some(order) =>
        chargeOrder(order)
    }

  private def chargeOrder(order: Order): F[PaymentResult] = {
    val fields = orderFields(order)
    log.info("charging order", fields) *>
      log.debug(
        s"order detail: $order"
      ) *> // by-name: only built when DEBUG is enabled
      gateway
        .charge(order.card, order.total, order.id)
        .timeout(gatewayTimeout)
        .adaptError {
          case e: PaymentError     => e
          case e: TimeoutException =>
            PaymentError.GatewayUnavailable(
              s"no response within $gatewayTimeout",
              e
            )
          case e => PaymentError.GatewayUnavailable(e.getMessage, e)
        }
        .timed
        .attempt
        .flatMap {
          case Right((elapsed, result @ PaymentResult.Approved(tx))) =>
            orders.updateStatus(order.id, OrderStatus.Paid(tx)) *>
              log
                .info(
                  "payment approved",
                  fields,
                  data("transactionId", tx.value),
                  duration(elapsed)
                )
                .as(result)
          case Right((elapsed, result @ PaymentResult.Declined(reason))) =>
            orders.updateStatus(order.id, OrderStatus.PaymentFailed(reason)) *>
              log
                .warn(
                  "payment declined",
                  fields,
                  data("declineReason", reason.toString),
                  duration(elapsed)
                )
                .as(result)
          case Left(e) =>
            log.error("payment gateway call failed", e, fields) *> e.raiseError
        }
  }

  private def duration(elapsed: FiniteDuration): LogFeature =
    data("durationMs", elapsed.toMillis)

  /** Structured fields for every log line about an order. Card is masked by
    * CardToken.toString.
    */
  private def orderFields(order: Order): LogFeature = data(
    Map(
      "orderId" -> order.id.value,
      "customerId" -> order.customerId.value,
      "amountCents" -> order.total.cents,
      "currency" -> order.total.currency.toString,
      "itemCount" -> order.items.size,
      "card" -> order.card.toString
    )
  )
}
