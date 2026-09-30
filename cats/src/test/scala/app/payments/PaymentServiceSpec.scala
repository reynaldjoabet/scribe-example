package app.payments

import app.domain.*
import app.logging.{Log, LogContext}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.handler.LogHandler
import scribe.{Level, LogRecord, Logger}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class PaymentServiceSpec extends AnyWordSpec with Matchers {
  private val records = new ConcurrentLinkedQueue[LogRecord]
  Logger("test.payments").orphan().clearHandlers().withMinimumLevel(Level.Info)
    .withHandler(LogHandler(Level.Trace)(r => records.add(r))).replace()

  private def run[A](f: (PaymentService[IO], OrderRepository[IO], LogContext) => IO[A]): A = {
    records.clear()
    (for {
      ctx <- LogContext.create
      repo <- OrderRepository.inMemory[IO](SampleData.orders)
      log = Log.named[IO]("test.payments", ctx)
      a <- f(new PaymentService[IO](repo, new FakePaymentGateway[IO](log), log), repo, ctx)
    } yield a).unsafeRunSync()
  }

  private def logged: List[(Level, String, Map[String, Any])] = records.asScala.toList.map { r =>
    (r.level, r.messages.head.logOutput.plainText, r.data.map((k, v) => k -> v()))
  }

  "PaymentService" should {
    "approve, mark the order paid, and log with order + request context" in {
      val (result, status) = run { (svc, repo, ctx) =>
        ctx.scoped("requestId" -> "req-1")(svc.charge(OrderId("ord-1")))
          .flatMap(r => repo.find(OrderId("ord-1")).map(o => r -> o.map(_.status)))
      }
      result shouldBe a[PaymentResult.Approved]
      status shouldBe Some(OrderStatus.Paid(result.asInstanceOf[PaymentResult.Approved].transactionId))
      val approved = logged.find(_._2 == "payment approved").get
      approved._1 shouldBe Level.Info
      approved._3("requestId") shouldBe "req-1"
      approved._3("orderId") shouldBe "ord-1"
      approved._3("amountCents") shouldBe 4748L // 2 x 19.99 + 3 x 2.50
      approved._3("card") shouldBe "CardToken(****4242)"
      approved._3 should contain key "durationMs"
    }
    "return declines as results and log them at WARN" in {
      run((svc, _, _) => svc.charge(OrderId("ord-2"))) shouldBe PaymentResult.Declined(DeclineReason.InsufficientFunds)
      logged.map(r => r._1 -> r._2) should contain(Level.Warn -> "payment declined")
    }
    "wrap gateway failures and log them at ERROR with the cause" in {
      val err = run((svc, _, _) => svc.charge(OrderId("ord-4")).attempt).left.toOption.get
      err shouldBe a[PaymentError.GatewayUnavailable]
      err.getCause shouldBe a[java.net.SocketTimeoutException]
      logged.map(r => r._1 -> r._2) should contain(Level.Error -> "payment gateway call failed")
    }
    "reject unknown and already-paid orders" in {
      run((svc, _, _) => svc.charge(OrderId("nope")).attempt).left.toOption.get shouldBe a[PaymentError.OrderNotFound]
      run((svc, _, _) => svc.charge(OrderId("ord-6")).attempt).left.toOption.get shouldBe a[PaymentError.AlreadyPaid]
    }
  }
}
