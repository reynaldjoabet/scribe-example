package app

import app.domain.SampleData
import app.http.PaymentRoutes
import app.logging.{RequestContext, ZioLogging}
import app.payments.{FakePaymentGateway, OrderRepository, PaymentService}
import zio.*
import zio.http.*

object Main extends ZIOAppDefault {
  // Scribe setup + ZIO logs routed to Scribe, before anything else runs; logs flushed when the app stops
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = ZioLogging.live

  def run: ZIO[Any, Throwable, Nothing] =
    ZIO
      .serviceWithZIO[PaymentService](payments => Server.serve(RequestContext(PaymentRoutes(payments))))
      .provide(
        Server.defaultWithPort(8081), // 8081 so it can run next to the cats app on 8080
        PaymentService.live,
        OrderRepository.inMemory(SampleData.orders),
        FakePaymentGateway.live
      )
}
