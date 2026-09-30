package app.logging

import io.circe.{Json, JsonObject}
import scribe.LogRecord
import scribe.json.ScribeCirceJsonSupport
import scribe.mdc.MDC

/**
 * Scribe's circe format, but with `data` as a JSON object with typed values (Scribe emits an array of string pairs,
 * which log backends can't index) and without the always-empty `mdc` / `column` fields.
 */
object JsonLogFormat extends ScribeCirceJsonSupport {
  override def jsonExtras(record: LogRecord, json: Json): Json = json.mapObject { o =>
    val data = (MDC.map ++ record.data).iterator.map((k, v) => k -> toJson(v())).toList
    o.remove("mdc").remove("column").add("data", Json.fromJsonObject(JsonObject.fromIterable(data)))
  }

  private def toJson(value: Any): Json = if (value.asInstanceOf[AnyRef] eq null) Json.Null else value match {
    case j: Json => j
    case s: String => Json.fromString(s)
    case i: Int => Json.fromInt(i)
    case l: Long => Json.fromLong(l)
    case d: Double => Json.fromDoubleOrString(d)
    case b: Boolean => Json.fromBoolean(b)
    case other => Json.fromString(other.toString)
  }
}
