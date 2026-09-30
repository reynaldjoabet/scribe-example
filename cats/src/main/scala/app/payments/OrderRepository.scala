package app.payments

import app.domain.{Order, OrderId, OrderStatus}
import cats.effect.{Ref, Sync}
import cats.syntax.all.*

trait OrderRepository[F[_]] {
  def find(id: OrderId): F[Option[Order]]

  def updateStatus(id: OrderId, status: OrderStatus): F[Unit]
}

object OrderRepository {
  def inMemory[F[_]: Sync](seed: List[Order]): F[OrderRepository[F]] =
    Ref.of[F, Map[OrderId, Order]](seed.map(o => o.id -> o).toMap).map { ref =>
      new OrderRepository[F] {
        def find(id: OrderId): F[Option[Order]] = ref.get.map(_.get(id))

        def updateStatus(id: OrderId, status: OrderStatus): F[Unit] =
          ref.update(orders => orders.updatedWith(id)(_.map(_.copy(status = status))))
      }
    }
}
