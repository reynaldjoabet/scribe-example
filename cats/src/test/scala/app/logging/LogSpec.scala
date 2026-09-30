package app.logging

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.handler.LogHandler
import scribe.{Level, LogRecord, Logger}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class LogSpec extends AnyWordSpec with Matchers {
  private val records = new ConcurrentLinkedQueue[LogRecord]
  Logger("test.capture")
    .orphan()
    .clearHandlers()
    .withMinimumLevel(Level.Info)
    .withHandler(LogHandler(Level.Trace)(r => records.add(r)))
    .replace()

  "Log" should {
    "attach fiber-local context, including across fiber boundaries" in {
      records.clear()
      (for {
        ctx <- LogContext.create
        log = Log.named[IO]("test.capture", ctx)
        _ <- ctx.scoped("requestId" -> "r1")(
          IO.cede *> log.info("inside").start.flatMap(_.join)
        )
        _ <- log.info("outside")
      } yield ()).unsafeRunSync()
      val rs = records.asScala.toList
      rs.map(_.get("requestId")) shouldBe List(Some("r1"), None)
    }
    "not evaluate messages for disabled levels" in {
      records.clear()
      var evaluated = false
      (for {
        ctx <- LogContext.create
        _ <- Log.named[IO]("test.capture", ctx).debug {
          evaluated = true; "nope"
        }
      } yield ()).unsafeRunSync()
      evaluated shouldBe false
      records.isEmpty shouldBe true
    }
    "pick up runtime level changes" in {
      records.clear()
      val ctx = LogContext.create.unsafeRunSync()
      val log = Log.named[IO]("test.capture", ctx)
      Logger("test.capture").withMinimumLevel(Level.Debug).replace()
      log.debug("now visible").unsafeRunSync()
      records.asScala.map(_.level) shouldBe List(Level.Debug)
    }
  }
}
