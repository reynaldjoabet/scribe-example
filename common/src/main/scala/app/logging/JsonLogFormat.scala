package app.logging

import io.circe.Json
import scribe.LogRecord
import scribe.mdc.MDC
import scribe.message.Message
import scribe.output.format.OutputFormat
import scribe.output.{LogOutput, TextOutput}
import scribe.throwable.Trace
import scribe.writer.Writer

import java.time.{Instant, ZoneId}

/** Renders a LogRecord as one JSON line, written straight into a StringBuilder.
  *
  * Produces the same fields as Scribe's circe format (with `data` as an object
  * of typed values), but without building a circe AST first: that AST was the
  * single biggest cost per log line, and this runs on the calling (compute)
  * thread.
  */
object JsonLogFormat {
  def writer(inner: Writer): Writer = new Writer {
    override def write(
        record: LogRecord,
        output: LogOutput,
        outputFormat: OutputFormat
    ): Unit =
      inner.write(record, new TextOutput(render(record)), outputFormat)
  }

  def render(record: LogRecord): String = {
    val sb = new java.lang.StringBuilder(512)
    sb.append("{\"level\":")
    string(sb, record.level.name)
    sb.append(",\"levelValue\":").append(record.levelValue)
    sb.append(",\"message\":")
    messages(sb, record)
    sb.append(",\"fileName\":")
    string(sb, record.fileName)
    sb.append(",\"className\":")
    string(sb, record.className)
    sb.append(",\"methodName\":")
    if (record.methodName.isEmpty) sb.append("null")
    else string(sb, record.methodName.get)
    sb.append(",\"line\":")
    if (record.line.isEmpty) sb.append("null")
    else sb.append(record.line.get.intValue)
    sb.append(",\"data\":")
    data(sb, record)
    sb.append(",\"trace\":")
    traces(sb, record)
    sb.append(",\"timeStamp\":").append(record.timeStamp)
    dateTime(sb, record.timeStamp)
    sb.append('}').toString
  }

  // Same selection as Scribe's JSON support: text messages only (stack traces go to "trace"); null / value / array
  private def messages(sb: java.lang.StringBuilder, record: LogRecord): Unit = {
    val texts = record.messages.collect {
      case m: Message[?] if !m.value.isInstanceOf[Throwable] => m
    }
    texts match {
      case Nil           => sb.append("null"): Unit
      case single :: Nil => message(sb, single)
      case many          =>
        sb.append('[')
        many.zipWithIndex.foreach { (m, i) =>
          if (i > 0) sb.append(',')
          message(sb, m)
        }
        sb.append(']'): Unit
    }
  }

  private def message(sb: java.lang.StringBuilder, m: Message[?]): Unit =
    m.value match {
      case json: Json => sb.append(json.noSpaces): Unit
      case _          => string(sb, m.logOutput.plainText)
    }

  private def data(sb: java.lang.StringBuilder, record: LogRecord): Unit = {
    val mdc = MDC.map
    val all = if (mdc.isEmpty) record.data else mdc ++ record.data
    sb.append('{')
    var first = true
    all.foreach { (key, value) =>
      if (!first) sb.append(',')
      first = false
      string(sb, key)
      sb.append(':')
      typed(sb, value())
    }
    sb.append('}'): Unit
  }

  private def typed(sb: java.lang.StringBuilder, value: Any): Unit =
    if (value.asInstanceOf[AnyRef] eq null) sb.append("null"): Unit
    else
      value match {
        case s: String => string(sb, s)
        case i: Int    => sb.append(i): Unit
        case l: Long   => sb.append(l): Unit
        case d: Double =>
          if (d.isNaN || d.isInfinite) string(sb, d.toString)
          else sb.append(d): Unit
        case b: Boolean => sb.append(b): Unit
        case j: Json    => sb.append(j.noSpaces): Unit
        case other      => string(sb, other.toString)
      }

  private def traces(sb: java.lang.StringBuilder, record: LogRecord): Unit =
    record.messages.map(_.value).collect { case t: Trace => t } match {
      case Nil           => sb.append("null"): Unit
      case single :: Nil => trace(sb, single)
      case many          =>
        sb.append('[')
        many.zipWithIndex.foreach { (t, i) =>
          if (i > 0) sb.append(',')
          trace(sb, t)
        }
        sb.append(']'): Unit
    }

  private def trace(sb: java.lang.StringBuilder, t: Trace): Unit = {
    sb.append("{\"className\":")
    string(sb, t.className)
    sb.append(",\"message\":")
    if (t.message.isEmpty) sb.append("null") else string(sb, t.message.get)
    sb.append(",\"elements\":[")
    t.elements.zipWithIndex.foreach { (e, i) =>
      if (i > 0) sb.append(',')
      sb.append("{\"class\":")
      string(sb, e.`class`)
      sb.append(",\"fileName\":")
      string(sb, e.fileName)
      sb.append(",\"method\":")
      string(sb, e.method)
      sb.append(",\"line\":").append(e.line)
      sb.append('}')
    }
    sb.append("],\"cause\":")
    if (t.cause.isEmpty) sb.append("null") else trace(sb, t.cause.get)
    sb.append('}'): Unit
  }

  /** JSON string with escaping; appends the whole string at once when nothing
    * needs escaping (the common case).
    */
  private def string(sb: java.lang.StringBuilder, s: String): Unit =
    if (s eq null) sb.append("null"): Unit
    else {
      sb.append('"')
      var clean = true
      var i = 0
      while (clean && i < s.length) {
        val c = s.charAt(i)
        if (c < ' ' || c == '"' || c == '\\') clean = false
        i += 1
      }
      if (clean) sb.append(s)
      else {
        i = 0
        while (i < s.length) {
          s.charAt(i) match {
            case '"'          => sb.append("\\\"")
            case '\\'         => sb.append("\\\\")
            case '\n'         => sb.append("\\n")
            case '\r'         => sb.append("\\r")
            case '\t'         => sb.append("\\t")
            case '\b'         => sb.append("\\b")
            case '\f'         => sb.append("\\f")
            case c if c < ' ' =>
              sb.append("\\u00")
                .append(Hex.charAt(c >> 4))
                .append(Hex.charAt(c & 0xf))
            case c => sb.append(c)
          }
          i += 1
        }
      }
      sb.append('"'): Unit
    }

  private val Hex = "0123456789abcdef"

  // "date" / "time" match Scribe's format ("2026-09-30", "20:36:44.999+0400", local time zone). Everything but the
  // milliseconds is cached per thread for the current second, so most lines skip date/time arithmetic entirely.
  private final class SecondCache {
    var second: Long = Long.MinValue
    var date: String = ""
    var hms: String = ""
    var zone: String = ""
  }

  private val secondCache = ThreadLocal.withInitial(() => new SecondCache)

  private def dateTime(sb: java.lang.StringBuilder, millis: Long): Unit = {
    val cache = secondCache.get()
    val second = Math.floorDiv(millis, 1000L)
    if (second != cache.second) {
      val zoned = Instant.ofEpochSecond(second).atZone(ZoneId.systemDefault())
      val offset = zoned.getOffset.getTotalSeconds
      val abs = Math.abs(offset)
      cache.date = zoned.toLocalDate.toString
      cache.hms =
        f"${zoned.getHour}%02d:${zoned.getMinute}%02d:${zoned.getSecond}%02d."
      cache.zone =
        f"${if (offset < 0) '-' else '+'}${abs / 3600}%02d${abs / 60 % 60}%02d"
      cache.second = second
    }
    val ms = Math.floorMod(millis, 1000L)
    sb.append(",\"date\":\"")
      .append(cache.date)
      .append("\",\"time\":\"")
      .append(cache.hms)
    if (ms < 100) sb.append('0')
    if (ms < 10) sb.append('0')
    sb.append(ms).append(cache.zone).append('"'): Unit
  }
}
