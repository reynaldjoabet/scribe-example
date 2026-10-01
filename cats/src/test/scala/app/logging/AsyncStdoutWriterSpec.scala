package app.logging

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scribe.output.TextOutput
import scribe.output.format.ASCIIOutputFormat
import scribe.{Level, LogRecord}

import java.io.{ByteArrayOutputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{CountDownLatch, TimeUnit}

class AsyncStdoutWriterSpec extends AnyWordSpec with Matchers {

  /** Stands in for a stalled stdout: every write blocks until `release`, then
    * output is captured.
    */
  private final class StalledStream extends OutputStream {
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val captured = new ByteArrayOutputStream()
    override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      entered.countDown()
      release.await()
      captured.synchronized(captured.write(b, off, len))
    }
    def lines: List[String] = captured
      .synchronized(captured.toString(StandardCharsets.UTF_8))
      .linesIterator
      .toList
  }

  private def write(
      writer: AsyncStdoutWriter,
      level: Level,
      text: String
  ): Unit =
    writer.write(
      LogRecord.simple(text, "", "", level = level),
      new TextOutput(text),
      ASCIIOutputFormat
    )

  "AsyncStdoutWriter" should {
    "shed INFO first and keep room for WARN/ERROR when stdout stalls" in {
      val stream = new StalledStream
      val writer = new AsyncStdoutWriter(
        capacity = 100,
        warnMaxWaitMillis = 50L,
        stream = stream
      )

      write(
        writer,
        Level.Info,
        "first"
      ) // the worker takes it and blocks writing to the stream
      stream.entered.await(5, TimeUnit.SECONDS) shouldBe true
      (1 to 100).foreach(i =>
        write(writer, Level.Info, s"info-$i")
      ) // 80 queued, the rest shed (80% threshold)
      (1 to 20).foreach(i =>
        write(writer, Level.Error, s"error-$i")
      ) // fit in the reserved 20%
      write(
        writer,
        Level.Error,
        "error-overflow"
      ) // queue full: waits 50ms, then dropped

      writer.dropped shouldBe 21L // 20 INFO shed + 1 ERROR that found no room
      stream.release.countDown()
      writer.close()

      val lines = stream.lines
      lines.count(_.startsWith("info-")) shouldBe 80
      lines.filter(_.startsWith("error-")) shouldBe (1 to 20).map(i =>
        s"error-$i"
      )
      lines.head shouldBe "first"
    }
    "write everything, in order, when the queue has room" in {
      val captured = new ByteArrayOutputStream()
      val writer = new AsyncStdoutWriter(
        capacity = 2000,
        stream = captured
      ) // room for the whole burst
      (1 to 1000).foreach(i =>
        write(writer, if (i % 10 == 0) Level.Error else Level.Info, s"line-$i")
      )
      writer.close()
      writer.dropped shouldBe 0L
      captured
        .toString(StandardCharsets.UTF_8)
        .linesIterator
        .toList shouldBe (1 to 1000).map(i => s"line-$i")
    }
  }
}
