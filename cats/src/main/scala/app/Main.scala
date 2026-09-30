package app

import app.domain.SampleData
import app.http.PaymentRoutes
import app.logging.CatsLogging
import app.logging.Log
import app.logging.LogContext
import app.logging.RequestContext
import app.payments.FakePaymentGateway
import app.payments.OrderRepository
import app.payments.PaymentService
import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object Main extends IOApp.Simple {
  // http4s/Ember log through log4cats -> SLF4J 2 -> scribe-slf4j2 -> the same JSON stdout pipeline
  given LoggerFactory[IO] = Slf4jFactory.create[IO]

  def run: IO[Unit] = CatsLogging.resource[IO].use { _ =>
    for {
      ctx <- LogContext.create
      orders <- OrderRepository.inMemory[IO](SampleData.orders)
      gateway = new FakePaymentGateway[IO](
        Log.forClass[IO](classOf[FakePaymentGateway[?]], ctx)
      )
      payments = new PaymentService[IO](
        orders,
        gateway,
        Log.forClass[IO](classOf[PaymentService[?]], ctx)
      )
      app = RequestContext(ctx)(
        new PaymentRoutes[IO](payments).routes
      ).orNotFound
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(app)
        .build
        .useForever
    } yield ()
  }
}
