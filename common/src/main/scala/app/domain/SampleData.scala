package app.domain

/** Seed orders covering each path through the FakePaymentGateway (see its card rules). */
object SampleData {
  private def order(id: String, last4: String, first: LineItem, rest: LineItem*): Order =
    Order.create(OrderId(id), CustomerId(s"cust-$id"), ::(first, rest.toList), CardToken(s"tok_$last4", last4))
      .fold(e => sys.error(s"Invalid sample order $id: $e"), identity)

  private val usd = (cents: Long) => Money(cents, Currency.USD)

  val orders: List[Order] = List(
    order("ord-1", "4242", LineItem(Sku("book"), 2, usd(1999)), LineItem(Sku("pen"), 3, usd(250))),
    order("ord-2", "0002", LineItem(Sku("laptop"), 1, usd(129900))), // insufficient funds
    order("ord-3", "0069", LineItem(Sku("phone"), 1, usd(79900))),   // card expired
    order("ord-4", "0119", LineItem(Sku("tv"), 1, usd(49900))),      // gateway down
    order("ord-5", "4242", LineItem(Sku("watch"), 1, usd(2500000))), // fraud check
    order("ord-6", "4242", LineItem(Sku("mug"), 1, usd(1200)))
      .copy(status = OrderStatus.Paid(TransactionId("tx-existing"))) // already paid
  )
}
