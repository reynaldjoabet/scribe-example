package app.logging

import scribe.mdc.{MDC, MDCValue}

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** Scribe's MDCMap (the thread-local MDC behind org.slf4j.MDC and
  * `scribe.mdc.MDC`), except that `map` costs nothing when the MDC is empty.
  *
  * Every JSON line reads the MDC (JsonLogFields.data), and Scribe's MDCMap
  * copies its ConcurrentHashMap into a new Scala Map on every read, even when
  * empty. Under cats-effect and ZIO the thread-local MDC is almost always
  * empty, so that copy was pure overhead on every log line.
  */
final class LeanMDCMap(parent: Option[MDC]) extends MDC {
  private val values = new ConcurrentHashMap[String, () => Any]

  override def map: Map[String, () => Any] =
    if (values.isEmpty) Map.empty else values.asScala.toMap

  override def get(key: String): Option[() => Any] =
    Option(values.get(key)).orElse(parent.flatMap(_.get(key)))

  override def update(key: String, value: => Any): Option[Any] =
    Option(values.put(key, () => value)).map(_())

  override def set(key: String, value: Option[Any]): Option[Any] =
    value match {
      case Some(v) => update(key, v)
      case None    => remove(key)
    }

  override def context[Return](
      kv: (String, MDCValue)*
  )(f: => Return): Return = {
    val previous = kv.map((key, value) => key -> update(key, value.value()))
    try f
    finally previous.foreach((key, value) => set(key, value): Unit)
  }

  override def remove(key: String): Option[Any] =
    Option(values.remove(key)).map(_())

  override def contains(key: String): Boolean = values.containsKey(key)

  override def clear(): Unit = values.clear()
}

object LeanMDCMap {

  /** Makes Scribe create a LeanMDCMap for each thread's MDC. Call before
    * logging starts: a thread that already has an MDC keeps it.
    */
  def install(): Unit = MDC.creator = parent => new LeanMDCMap(parent)
}
