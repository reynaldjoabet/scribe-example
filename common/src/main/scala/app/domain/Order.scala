package app.domain

/** Tokenized card from the payment provider. Never holds the PAN; toString is
  * masked so it's safe to log.
  */
final case class CardToken(token: String, last4: String) {
  override def toString: String = s"CardToken(****$last4)"
}

final case class LineItem(sku: Sku, quantity: Int, unitPrice: Money) {
  def total: Money = unitPrice * quantity
}

enum OrderStatus {
  case Pending
  case Paid(transactionId: TransactionId)
  case PaymentFailed(reason: DeclineReason)
}

final case class Order(
    id: OrderId,
    customerId: CustomerId,
    items: ::[LineItem],
    card: CardToken,
    status: OrderStatus
) {
  def currency: Currency = items.head.unitPrice.currency

  def total: Money = items.foldLeft(Money.zero(currency))(_ + _.total)
}

object Order {

  /** Validating constructor: all items share one currency, quantities and
    * prices are positive.
    */
  def create(
      id: OrderId,
      customerId: CustomerId,
      items: ::[LineItem],
      card: CardToken
  ): Either[String, Order] = {
    val currency = items.head.unitPrice.currency
    if (items.exists(_.unitPrice.currency != currency))
      Left("All line items must use the same currency")
    else if (items.exists(_.quantity <= 0)) Left("Quantities must be positive")
    else if (items.exists(!_.unitPrice.isPositive))
      Left("Prices must be positive")
    else Right(Order(id, customerId, items, card, OrderStatus.Pending))
  }
}
