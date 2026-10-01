package app.logging

import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.mdc.MDC
import scribe.message.LoggableMessage
import scribe.output.TextOutput
import scribe.throwable.TraceLoggableMessage
import scribe.{Level, LogRecord}

/** JsoniterFormatter must produce exactly the same bytes as JsonFormatter.
  */
class JsoniterFormatterSpec extends AnyWordSpec with Matchers {
  private def record(
      messages: List[LoggableMessage],
      data: Map[String, () => Any] = Map.empty,
      level: Level = Level.Info,
      methodName: Option[String] = Some("charge"),
      line: Option[Int] = Some(42),
      timeStamp: Long = 1790786235608L
  ): LogRecord =
    LogRecord(
      level,
      level.value,
      messages,
      "PaymentService.scala",
      "app.payments.PaymentService",
      methodName,
      line,
      column = None,
      data = data,
      timeStamp = timeStamp
    )

  private def text(s: String): LoggableMessage =
    LoggableMessage.string2LoggableMessage(s)

  private def assertIdentical(r: LogRecord) =
    JsoniterFormatter.render(r) shouldBe JsonFormatter.render(r)

  "JsoniterFormatter" should {
    "match JsonFormatter for typed data values, including awkward doubles" in assertIdentical(
      record(
        List(text("charging order")),
        Map(
          "orderId" -> (() => "ord-1"),
          "amountCents" -> (() => 4748L),
          "items" -> (() => 3),
          "flag" -> (() => false),
          "ratio" -> (() => 0.25),
          "big" -> (() => 1.0e10),
          "small" -> (() => 1.0e-5),
          "negZero" -> (() => -0.0),
          "hundred" -> (() => 100.0),
          "pi" -> (() => 3.141592653589793),
          "nan" -> (() => Double.NaN),
          "inf" -> (() => Double.NegativeInfinity),
          "missing" -> (() => null),
          "decimal" -> (() => BigDecimal("12.50")),
          "json" -> (() =>
            Json.obj("a" -> Json.fromInt(1), "b" -> Json.arr(Json.True))
          )
        )
      )
    )
    "match JsonFormatter for strings that need escaping" in assertIdentical(
      record(
        List(
          text(
            "quote \" backslash \\ newline \n cr \r tab \t bs \b ff \f ctrl \u0001\u001f del \u007f é 😀 中文"
          )
        ),
        Map("path \"key\"" -> (() => "C:\\tmp\\\"x\""))
      )
    )
    "match JsonFormatter for errors with causes and multiple messages" in {
      val cause = new java.net.SocketTimeoutException("Read timed out")
      assertIdentical(
        record(
          List(
            text("payment failed"),
            text("second"),
            TraceLoggableMessage(new IllegalStateException("boom", cause))
          ),
          level = Level.Error
        )
      )
    }
    "match JsonFormatter with no method or line, a JSON message, and MDC values" in {
      MDC("tenant") = "acme"
      try
        assertIdentical(
          record(
            List(
              LoggableMessage[Json](j => new TextOutput(j.noSpaces))(
                Json.obj("event" -> Json.fromString("x"))
              )
            ),
            methodName = None,
            line = None
          )
        )
      finally MDC.remove("tenant"): Unit
    }
    "match JsonFormatter for timestamps" in
      List(0L, 1790786235000L, 1790786235007L, 1790786235042L, 1790812800000L)
        .foreach { ts =>
          assertIdentical(record(List(text("tick")), timeStamp = ts))
        }
    "not fail on a lone surrogate (invalid UTF-16), falling back to JsonFormatter" in {
      val r = record(List(text("truncated emoji: \uD83D")))
      noException should be thrownBy JsoniterFormatter.render(r)
      assertIdentical(r)
    }
  }
}
