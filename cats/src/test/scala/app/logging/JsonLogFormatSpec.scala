package app.logging

import io.circe.{Json, Printer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.mdc.MDC
import scribe.message.LoggableMessage
import scribe.output.TextOutput
import scribe.throwable.TraceLoggableMessage
import scribe.{Level, LogRecord}

/** The direct JsonLogFormat must produce exactly what the previous circe-based
  * format produced.
  */
class JsonLogFormatSpec extends AnyWordSpec with Matchers {
  private val sortedKeys = Printer.noSpaces.copy(sortKeys = true)

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

  private def assertSame(r: LogRecord) = {
    val direct = JsonLogFormat.render(r)
    direct should not include "\n"
    val parsed = io.circe.jawn
      .parse(direct)
      .fold(e => fail(s"invalid JSON: $e\n$direct"), identity)
    sortedKeys.print(parsed) shouldBe sortedKeys.print(
      CirceJsonLogFormat.logRecord2Json(r)
    )
  }

  private def text(s: String): LoggableMessage =
    LoggableMessage.string2LoggableMessage(s)

  "JsonLogFormat" should {
    "match the circe format for typed data values" in assertSame(
      record(
        List(text("charging order")),
        Map(
          "orderId" -> (() => "ord-1"),
          "amountCents" -> (() => 4748L),
          "items" -> (() => 3),
          "ratio" -> (() => 0.25),
          "nan" -> (() => Double.NaN),
          "flag" -> (() => true),
          "big" -> (() => BigDecimal("12.50")),
          "json" -> (() => Json.obj("a" -> Json.fromInt(1)))
        )
      )
    )
    // The circe format threw a NullPointerException here (Scribe's base implementation calls toString on every data
    // value), failing the effect that logged. The direct format writes JSON null.
    "render null data values as JSON null instead of throwing" in {
      val r = record(List(text("with null")), Map("couponCode" -> (() => null)))
      an[NullPointerException] should be thrownBy CirceJsonLogFormat
        .logRecord2Json(r)
      JsonLogFormat.render(r) should include("\"data\":{\"couponCode\":null}")
    }
    "match the circe format for strings that need escaping" in assertSame(
      record(
        List(
          text(
            "quote \" backslash \\ newline \n tab \t ctrl \u0001 unicode é 😀"
          )
        ),
        Map("path" -> (() => "C:\\tmp\\\"x\""))
      )
    )
    "match the circe format for errors with causes and multiple messages" in {
      val cause = new java.net.SocketTimeoutException("Read timed out")
      assertSame(
        record(
          List(
            text("payment failed"),
            text("second message"),
            TraceLoggableMessage(new IllegalStateException("boom", cause))
          ),
          level = Level.Error
        )
      )
    }
    "match the circe format with no method or line, and a JSON message" in assertSame(
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
    "include thread-local MDC values like the circe format" in {
      MDC("tenant") = "acme"
      try
        assertSame(
          record(List(text("with mdc")), Map("orderId" -> (() => "ord-1")))
        )
      finally MDC.remove("tenant"): Unit
    }
    "match date and time fields across milliseconds and seconds" in
      List(1790786235000L, 1790786235007L, 1790786235042L, 1790786235999L,
        1790786236001L, 0L).foreach { ts =>
        assertSame(record(List(text("tick")), timeStamp = ts))
      }
  }
}
