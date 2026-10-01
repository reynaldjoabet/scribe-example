package app.logging

import scribe.LogRecord
import scribe.mdc.MDC
import scribe.message.Message
import scribe.throwable.Trace

import java.time.{LocalDateTime, ZoneOffset}

/** What goes into a JSON log line, shared by every JSON formatter
  * (JsonFormatter, JsoniterFormatter) so their output can't drift apart. Each
  * formatter only decides how to write these values.
  */
private[logging] object JsonLogFields {

  /** Text messages only (stack traces go to "trace"): the same selection as
    * Scribe's own JSON support.
    */
  def textMessages(record: LogRecord): List[Message[?]] =
    record.messages.collect {
      case m: Message[?] if !m.value.isInstanceOf[Throwable] => m
    }

  def traces(record: LogRecord): List[Trace] =
    record.messages.map(_.value).collect { case t: Trace => t }

  /** Thread-local MDC (e.g. set via org.slf4j.MDC by a library) plus the
    * record's own data; the record wins.
    */
  def data(record: LogRecord): Map[String, () => Any] = {
    val mdc = MDC.map
    if (mdc.isEmpty) record.data else mdc ++ record.data
  }

  // "timestamp" is ISO-8601 in UTC with exactly 3 fractional digits ("2026-09-30T19:47:11.372Z"), which Datadog, Loki,
  // GCP and Elastic parse without extra pipeline config, and which sorts correctly as plain text. Everything but the
  // milliseconds is cached per thread for the current second, so most lines skip date arithmetic entirely.
  private final class SecondCache {
    var second: Long = Long.MinValue
    var prefix: String = "" // "2026-09-30T19:47:11."
  }

  private val secondCache = ThreadLocal.withInitial(() => new SecondCache)

  /** Appends the timestamp without quotes. */
  def appendTimestamp(sb: java.lang.StringBuilder, millis: Long): Unit = {
    val cache = secondCache.get()
    val second = Math.floorDiv(millis, 1000L)
    if (second != cache.second) {
      val t = LocalDateTime.ofEpochSecond(second, 0, ZoneOffset.UTC)
      cache.prefix =
        f"${t.getYear}%04d-${t.getMonthValue}%02d-${t.getDayOfMonth}%02dT" +
          f"${t.getHour}%02d:${t.getMinute}%02d:${t.getSecond}%02d."
      cache.second = second
    }
    val ms = Math.floorMod(millis, 1000L)
    sb.append(cache.prefix)
    if (ms < 100) sb.append('0'): Unit
    if (ms < 10) sb.append('0'): Unit
    sb.append(ms).append('Z'): Unit
  }

  def timestamp(millis: Long): String = {
    val sb = new java.lang.StringBuilder(24)
    appendTimestamp(sb, millis)
    sb.toString
  }
}
