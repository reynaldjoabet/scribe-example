package app.payments

import app.domain.{Order, OrderId, OrderStatus}
import zio.*

trait OrderRepository {
  def find(id: OrderId): UIO[Option[Order]]

  def updateStatus(id: OrderId, status: OrderStatus): UIO[Unit]
}

object OrderRepository {
  def inMemory(seed: List[Order]): ULayer[OrderRepository] = ZLayer {
    Ref.make(seed.map(o => o.id -> o).toMap).map { ref =>
      new OrderRepository {
        def find(id: OrderId): UIO[Option[Order]] = ref.get.map(_.get(id))

        def updateStatus(id: OrderId, status: OrderStatus): UIO[Unit] =
          ref.update(orders => orders.updatedWith(id)(_.map(_.copy(status = status))))
      }
    }
  }
}
