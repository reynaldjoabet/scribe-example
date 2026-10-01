package app.http

import app.domain.*
import app.LogCapture
import app.LogCapture.field
import app.logging.RequestContextMiddleware
import app.payments.{FakePaymentGateway, OrderRepository, PaymentService}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.Level
import scribe.throwable.Trace as ScribeTrace
import zio.*
import zio.http.*

class PaymentRoutesSpec extends AnyWordSpec with Matchers {
  private def charge(orderId: String): Response = Unsafe.unsafe { implicit u =>
    LogCapture
      .run(
        ZIO
          .serviceWithZIO[PaymentService] { payments =>
            ZIO.scoped(
              RequestContextMiddleware(PaymentRoutes(payments))
                .runZIO(
                  Request
                    .post(s"/orders/$orderId/charge", Body.empty)
                    .addHeader("X-Request-ID", s"req-$orderId")
                )
            )
          }
          .provide(
            PaymentService.live,
            OrderRepository.inMemory(SampleData.orders),
            FakePaymentGateway.live
          )
      )
      .getOrThrowFiberFailure()
  }

  "POST /orders/{id}/charge" should {
    "approve, echo the request id, and log with request + order context" in {
      val response = charge("ord-1")
      response.status shouldBe Status.Ok
      response.rawHeader("X-Request-ID") shouldBe Some("req-ord-1")
      val approved = LogCapture.record("payment approved")
      approved.level shouldBe Level.Info
      approved.className shouldBe "app.payments.PaymentService"
      approved.field("requestId") shouldBe Some("req-ord-1")
      approved.field("path") shouldBe Some("/orders/ord-1/charge")
      approved.field("orderId") shouldBe Some("ord-1")
      approved.field("amountCents") shouldBe Some(
        "4748"
      ) // 2 x 19.99 + 3 x 2.50
      approved.field("card") shouldBe Some("CardToken(****4242)")
      approved.field("transactionId") shouldBe defined
      approved.field("durationMs") shouldBe defined
    }
    "return 402 for declines and log them at WARN" in {
      charge("ord-2").status shouldBe Status.PaymentRequired
      val declined = LogCapture.record("payment declined")
      declined.level shouldBe Level.Warn
      declined.field("declineReason") shouldBe Some("InsufficientFunds")
    }
    "return 503 for gateway failures and log ERROR with the cause" in {
      charge("ord-4").status shouldBe Status.ServiceUnavailable
      val failed = LogCapture.record("payment gateway call failed")
      failed.level shouldBe Level.Error
      val trace = failed.messages
        .map(_.value)
        .collectFirst { case t: ScribeTrace => t }
        .get
      trace.className shouldBe "app.domain.PaymentError$GatewayUnavailable"
      trace.cause.map(_.className) shouldBe Some(
        "java.net.SocketTimeoutException"
      )
    }
    "return 404 and 409 for unknown and already-paid orders" in {
      charge("nope").status shouldBe Status.NotFound
      charge("ord-6").status shouldBe Status.Conflict
    }
  }
}
