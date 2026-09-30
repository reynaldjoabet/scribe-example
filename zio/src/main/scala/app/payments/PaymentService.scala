package app.payments

import app.domain.*
import zio.*

import java.util.concurrent.TimeoutException

/** Same behaviour as the cats PaymentService, written with ZIO's logging:
  * context goes in `ZIO.logAnnotate` (fiber-local, so every log inside the
  * block gets it) instead of per-call `data(...)`.
  */
final class PaymentService(
    orders: OrderRepository,
    gateway: PaymentGateway,
    gatewayTimeout: Duration
) {
  def charge(id: OrderId): IO[PaymentError, PaymentResult] =
    orders.find(id).flatMap {
      case None =>
        ZIO.logAnnotate("orderId", id.value)(
          ZIO.logWarning("order not found")
        ) *>
          ZIO.fail(PaymentError.OrderNotFound(id))
      case Some(order) =>
        ZIO.logAnnotate(orderAnnotations(order))(process(order))
    }

  private def process(order: Order): IO[PaymentError, PaymentResult] =
    order.status match {
      case _: OrderStatus.Paid =>
        ZIO.logWarning("order already paid") *> ZIO.fail(
          PaymentError.AlreadyPaid(order.id)
        )
      case _ if !order.total.isPositive =>
        ZIO.logWarning("order has invalid amount") *> ZIO.fail(
          PaymentError.InvalidAmount(order.id, order.total)
        )
      case _ =>
        chargeOrder(order)
    }

  private def chargeOrder(order: Order): IO[PaymentError, PaymentResult] =
    ZIO.logInfo("charging order") *>
      ZIO.logDebug(
        s"order detail: $order"
      ) *> // by-name: only built when DEBUG is enabled
      gateway
        .charge(order.card, order.total, order.id)
        .mapError {
          case e: PaymentError => e
          case e => PaymentError.GatewayUnavailable(e.getMessage, e)
        }
        .timeoutFail(
          PaymentError.GatewayUnavailable(
            s"no response within $gatewayTimeout",
            new TimeoutException()
          )
        )(gatewayTimeout)
        .timed
        .tapErrorCause(cause =>
          ZIO.logErrorCause("payment gateway call failed", cause)
        )
        .flatMap {
          case (elapsed, result @ PaymentResult.Approved(tx)) =>
            orders.updateStatus(order.id, OrderStatus.Paid(tx)) *>
              ZIO
                .logAnnotate(
                  LogAnnotation("transactionId", tx.value),
                  durationMs(elapsed)
                ) {
                  ZIO.logInfo("payment approved")
                }
                .as(result)
          case (elapsed, result @ PaymentResult.Declined(reason)) =>
            orders.updateStatus(order.id, OrderStatus.PaymentFailed(reason)) *>
              ZIO
                .logAnnotate(
                  LogAnnotation("declineReason", reason.toString),
                  durationMs(elapsed)
                ) {
                  ZIO.logWarning("payment declined")
                }
                .as(result)
        }

  private def durationMs(elapsed: Duration): LogAnnotation =
    LogAnnotation("durationMs", elapsed.toMillis.toString)

  /** Context for every log line about an order. Card is masked by
    * CardToken.toString.
    */
  private def orderAnnotations(order: Order): Set[LogAnnotation] = Set(
    LogAnnotation("orderId", order.id.value),
    LogAnnotation("customerId", order.customerId.value),
    LogAnnotation("amountCents", order.total.cents.toString),
    LogAnnotation("currency", order.total.currency.toString),
    LogAnnotation("itemCount", order.items.size.toString),
    LogAnnotation("card", order.card.toString)
  )
}

object PaymentService {
  val live: URLayer[OrderRepository & PaymentGateway, PaymentService] =
    ZLayer.fromFunction((orders: OrderRepository, gateway: PaymentGateway) =>
      new PaymentService(orders, gateway, 5.seconds)
    )
}
