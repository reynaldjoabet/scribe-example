package app.logging

import app.LogCapture
import app.LogCapture.field
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.Level
import scribe.throwable.Trace as ScribeTrace
import zio.*

class ScribeZLoggerSpec extends AnyWordSpec with Matchers {
  "ScribeZLogger" should {
    "map level, source location and annotations to the Scribe record" in {
      LogCapture.run(
        ZIO.logAnnotate("requestId", "r1")(ZIO.logWarning("hello"))
      )
      val r = LogCapture.record("hello")
      r.level shouldBe Level.Warn
      r.className should startWith("app.logging.ScribeZLoggerSpec")
      r.fileName shouldBe "ScribeZLoggerSpec.scala"
      r.line shouldBe defined
      r.field("requestId") shouldBe Some("r1")
    }
    "keep each call site's own location (they're cached per Trace)" in {
      val twoSites =
        ZIO.logInfo("site a") *>
          ZIO.logInfo("site b")
      for (_ <- 1 to 2) {
        LogCapture.run(twoSites)
        val (a, b) = (LogCapture.record("site a"), LogCapture.record("site b"))
        b.line.get shouldBe a.line.get + 1
        a.loggerName shouldBe Some(a.className)
      }
    }
    "carry annotations into forked fibers" in {
      LogCapture.run(
        ZIO.logAnnotate("requestId", "r2")(
          ZIO.logInfo("in child").fork.flatMap(_.join)
        )
      )
      LogCapture.record("in child").field("requestId") shouldBe Some("r2")
    }
    "not evaluate the message when Scribe has the level disabled" in {
      var evaluated = false
      LogCapture.run(ZIO.logDebug { evaluated = true; "nope" })
      evaluated shouldBe false
      LogCapture.records shouldBe empty
    }
    "attach the failure's Throwable as a stack trace" in {
      val boom = new IllegalStateException("boom")
      LogCapture.run(ZIO.logErrorCause("failed", Cause.fail(boom)))
      val traces = LogCapture.record("failed").messages.map(_.value).collect {
        case t: ScribeTrace => t
      }
      traces.map(_.className) shouldBe List("java.lang.IllegalStateException")
    }
    "record span durations" in {
      LogCapture.run(
        ZIO.logSpan("work")(ZIO.sleep(20.millis) *> ZIO.logInfo("done"))
      )
      LogCapture
        .record("done")
        .field("span.work")
        .map(_.asInstanceOf[Long])
        .get should be >= 20L
    }
  }
}
