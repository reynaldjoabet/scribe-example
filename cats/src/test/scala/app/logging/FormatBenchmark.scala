package app.logging

import scribe.message.LoggableMessage
import scribe.throwable.TraceLoggableMessage
import scribe.{Level, LogRecord}

import java.nio.charset.StandardCharsets

/** Single-thread cost of rendering one JSON log line: circe vs StringBuilder vs
  * jsoniter-scala.
  *
  * All formatters run in the same JVM in alternating rounds, so CPU throttling
  * or background load affects them equally. Usage: FormatBenchmark (results on
  * stdout).
  */
object FormatBenchmark {
  private def record(
      level: Level,
      messages: List[LoggableMessage],
      data: Map[String, () => Any]
  ): LogRecord =
    LogRecord(
      level,
      level.value,
      messages,
      "PaymentService.scala",
      "app.payments.PaymentService",
      Some("chargeOrder"),
      Some(39),
      column = None,
      data = data,
      timeStamp = System.currentTimeMillis()
    )

  // Like the payment app's "payment approved" line: request context + order fields
  private val info = record(
    Level.Info,
    List(LoggableMessage.string2LoggableMessage("payment approved")),
    Map[String, Any](
      "requestId" -> "req-7f3c2a9e",
      "method" -> "POST",
      "path" -> "/orders/ord-1/charge",
      "service" -> "payments",
      "orderId" -> "ord-1",
      "customerId" -> "cust-ord-1",
      "amountCents" -> 4748L,
      "currency" -> "USD",
      "itemCount" -> 2,
      "card" -> "CardToken(****4242)",
      "transactionId" -> "tx-5b1e0c9d-2f47-4c1a-9a57-0f3d8e2b6c11",
      "durationMs" -> 41L
    ).map((k, v) => k -> (() => v))
  )

  private def deep(n: Int): Throwable =
    if (n == 0)
      new IllegalStateException(
        "gateway failed",
        new java.net.SocketTimeoutException("Read timed out")
      )
    else deep(n - 1)

  // An error with a realistic ~40-frame stack trace and a cause
  private val error = record(
    Level.Error,
    List(
      LoggableMessage.string2LoggableMessage("payment gateway call failed"),
      TraceLoggableMessage(deep(30))
    ),
    Map[String, Any]("requestId" -> "req-7f3c2a9e", "orderId" -> "ord-4").map(
      (k, v) => k -> (() => v)
    )
  )

  // Each returns the line's length: String for the String-based pipeline, bytes for a byte-based one
  private val formatters: List[(String, LogRecord => Int)] = List(
    "circe (Scribe's support)" -> (r =>
      CirceJsonLogFormat
        .json2String(CirceJsonLogFormat.logRecord2Json(r))
        .length
    ),
    "StringBuilder -> String" -> (r => JsonLogFormat.render(r).length),
    "jsoniter -> String" -> (r => JsoniterJsonLogFormat.render(r).length),
    "StringBuilder -> UTF-8 bytes" -> (r =>
      JsonLogFormat.render(r).getBytes(StandardCharsets.UTF_8).length
    ),
    "jsoniter -> UTF-8 bytes" -> (r =>
      JsoniterJsonLogFormat.renderBytes(r).length
    )
  )

  @volatile private var sink = 0L

  private def nsPerOp(
      render: LogRecord => Int,
      r: LogRecord,
      n: Int
  ): Double = {
    var total = 0L
    val t0 = System.nanoTime()
    var i = 0
    while (i < n) {
      total += render(r)
      i += 1
    }
    val elapsed = System.nanoTime() - t0
    sink += total
    elapsed.toDouble / n
  }

  def main(args: Array[String]): Unit = {
    val cases = List(
      ("INFO, 12 fields", info, 400000),
      ("ERROR + 40-frame trace", error, 40000)
    )
    cases.foreach { (name, r, n) =>
      val bytes =
        JsonLogFormat.render(r).getBytes(StandardCharsets.UTF_8).length
      (1 to 5).foreach(_ =>
        formatters.foreach((_, f) => nsPerOp(f, r, n))
      ) // warm-up (JIT)
      val rounds = (1 to 7).map(_ => formatters.map((_, f) => nsPerOp(f, r, n)))
      println(
        s"\n$name ($bytes bytes per line), median of 7 alternating rounds:"
      )
      formatters.indices.foreach { i =>
        val sorted = rounds.map(_(i)).sorted
        val median = sorted(sorted.size / 2)
        println(
          f"  ${formatters(i)._1}%-30s $median%8.0f ns/line   (range ${sorted.head}%.0f–${sorted.last}%.0f)"
        )
      }
    }
    println(s"\n(sink $sink)")
  }
}
