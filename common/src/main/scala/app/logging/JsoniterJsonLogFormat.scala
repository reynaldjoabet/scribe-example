package app.logging

import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.circe.Json
import scribe.LogRecord
import scribe.message.Message
import scribe.output.format.OutputFormat
import scribe.output.{LogOutput, TextOutput}
import scribe.throwable.Trace
import scribe.writer.Writer

import java.nio.charset.StandardCharsets

/** Same JSON line as [[JsonLogFormat]] (byte for byte), written with
  * jsoniter-scala's JsonWriter instead of a StringBuilder. The codec is
  * hand-written because a record's `data` holds arbitrary types, which
  * jsoniter's derived codecs can't express.
  */
object JsoniterJsonLogFormat {
  def writer(inner: Writer): Writer = new Writer {
    override def write(
        record: LogRecord,
        output: LogOutput,
        outputFormat: OutputFormat
    ): Unit =
      inner.write(record, new TextOutput(render(record)), outputFormat)
  }

  def render(record: LogRecord): String =
    try writeToString(record)
    catch {
      // jsoniter rejects strings that aren't valid UTF-16 (e.g. a lone surrogate from a truncated emoji). A log call
      // must never fail because of its content, so fall back to the StringBuilder format, which passes them through.
      case _: JsonWriterException => JsonLogFormat.render(record)
    }

  /** UTF-8 bytes directly: jsoniter's native output, skipping the bytes ->
    * String -> bytes round trip.
    */
  def renderBytes(record: LogRecord): Array[Byte] =
    try writeToArray(record)
    catch {
      case _: JsonWriterException =>
        JsonLogFormat.render(record).getBytes(StandardCharsets.UTF_8)
    }

  private given JsonValueCodec[LogRecord] = new JsonValueCodec[LogRecord] {
    override def nullValue: LogRecord = null

    override def decodeValue(in: JsonReader, default: LogRecord): LogRecord =
      in.decodeError("LogRecord is encode-only")

    override def encodeValue(r: LogRecord, out: JsonWriter): Unit = {
      out.writeObjectStart()
      out.writeNonEscapedAsciiKey("timestamp")
      out.writeNonEscapedAsciiVal(LogJsonFields.timestamp(r.timeStamp))
      out.writeNonEscapedAsciiKey("level")
      string(out, r.level.name)
      out.writeNonEscapedAsciiKey("levelValue")
      out.writeVal(r.levelValue)
      out.writeNonEscapedAsciiKey("message")
      messages(out, r)
      out.writeNonEscapedAsciiKey("fileName")
      string(out, r.fileName)
      out.writeNonEscapedAsciiKey("className")
      string(out, r.className)
      out.writeNonEscapedAsciiKey("methodName")
      if (r.methodName.isEmpty) out.writeNull()
      else string(out, r.methodName.get)
      out.writeNonEscapedAsciiKey("line")
      if (r.line.isEmpty) out.writeNull() else out.writeVal(r.line.get.intValue)
      out.writeNonEscapedAsciiKey("data")
      data(out, r)
      out.writeNonEscapedAsciiKey("trace")
      traces(out, r)
      out.writeObjectEnd()
    }
  }

  private def messages(out: JsonWriter, r: LogRecord): Unit =
    LogJsonFields.textMessages(r) match {
      case Nil           => out.writeNull()
      case single :: Nil => message(out, single)
      case many          =>
        out.writeArrayStart()
        many.foreach(message(out, _))
        out.writeArrayEnd()
    }

  private def message(out: JsonWriter, m: Message[?]): Unit = m.value match {
    case json: Json => raw(out, json)
    case _          => string(out, m.logOutput.plainText)
  }

  private def data(out: JsonWriter, r: LogRecord): Unit = {
    out.writeObjectStart()
    LogJsonFields.data(r).foreach { (key, value) =>
      out.writeKey(key)
      typed(out, value())
    }
    out.writeObjectEnd()
  }

  private def typed(out: JsonWriter, value: Any): Unit =
    if (value.asInstanceOf[AnyRef] eq null) out.writeNull()
    else
      value match {
        case s: String => out.writeVal(s)
        case i: Int    => out.writeVal(i)
        case l: Long   => out.writeVal(l)
        case d: Double =>
          if (d.isNaN || d.isInfinite) out.writeVal(d.toString)
          else out.writeVal(d)
        case b: Boolean => out.writeVal(b)
        case j: Json    => raw(out, j)
        case other      => string(out, other.toString)
      }

  private def traces(out: JsonWriter, r: LogRecord): Unit =
    LogJsonFields.traces(r) match {
      case Nil           => out.writeNull()
      case single :: Nil => trace(out, single)
      case many          =>
        out.writeArrayStart()
        many.foreach(trace(out, _))
        out.writeArrayEnd()
    }

  private def trace(out: JsonWriter, t: Trace): Unit = {
    out.writeObjectStart()
    out.writeNonEscapedAsciiKey("className")
    string(out, t.className)
    out.writeNonEscapedAsciiKey("message")
    if (t.message.isEmpty) out.writeNull() else string(out, t.message.get)
    out.writeNonEscapedAsciiKey("elements")
    out.writeArrayStart()
    t.elements.foreach { e =>
      out.writeObjectStart()
      out.writeNonEscapedAsciiKey("class")
      string(out, e.`class`)
      out.writeNonEscapedAsciiKey("fileName")
      string(out, e.fileName)
      out.writeNonEscapedAsciiKey("method")
      string(out, e.method)
      out.writeNonEscapedAsciiKey("line")
      out.writeVal(e.line)
      out.writeObjectEnd()
    }
    out.writeArrayEnd()
    out.writeNonEscapedAsciiKey("cause")
    if (t.cause.isEmpty) out.writeNull() else trace(out, t.cause.get)
    out.writeObjectEnd()
  }

  private def string(out: JsonWriter, s: String): Unit =
    if (s eq null) out.writeNull() else out.writeVal(s)

  private def raw(out: JsonWriter, json: Json): Unit =
    out.writeRawVal(json.noSpaces.getBytes(StandardCharsets.UTF_8))
}
