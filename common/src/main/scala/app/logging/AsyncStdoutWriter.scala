package app.logging

import scribe.{Level, LogRecord}
import scribe.output.LogOutput
import scribe.output.format.{ASCIIOutputFormat, OutputFormat}
import scribe.writer.Writer

import java.io.{
  BufferedWriter,
  FileDescriptor,
  FileOutputStream,
  OutputStream,
  OutputStreamWriter
}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong}
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.util.control.NonFatal

/** Stdout writer that splits CPU work from blocking I/O:
  *   - Formatting (JSON rendering, etc.) runs on the calling thread, so it
  *     scales across all cores. That's CPU-bound work, which is fine on the
  *     cats-effect compute pool.
  *   - Only finished lines are queued. One worker thread writes them through a
  *     64KB buffer to the stdout file descriptor, flushing whenever the queue
  *     empties (large writes under load, low latency when quiet).
  *   - `close()` drains everything queued, then flushes.
  *
  * When stdout can't keep up (e.g. a backed-up log collector), it sheds noise
  * first instead of losing errors:
  *   - above 80% full, lines below WARN are dropped, keeping the remaining room
  *     for WARN/ERROR/FATAL
  *   - when completely full, WARN and above wait up to `importantWait` for
  *     space; lower levels are dropped at once
  * Drops are counted (`dropped`) and reported as a WARN line once stdout
  * catches up. A stalled stdout therefore never blocks callers for more than
  * `importantWait`, and only for WARN+ lines.
  *
  * Replaces Scribe's AsynchronousLogHandle, whose worker sleeps 1ms per record
  * (~1000 records/sec) and drops the rest. Use it with the default
  * SynchronousLogHandle.
  */
final class AsyncStdoutWriter(
    capacity: Int = 65536,
    importantWaitMillis: Long = 100L,
    stream: OutputStream = new FileOutputStream(
      FileDescriptor.out
    ) // overridable for tests
) extends Writer
    with AutoCloseable {

  private val queue = new ArrayBlockingQueue[String](capacity)
  // Approximate queue size: ArrayBlockingQueue.size() takes the queue lock, which every caller would contend on
  private val pending = new AtomicInteger(0)
  private val shedAbove = capacity - capacity / 5
  private val droppedTotal = new AtomicLong(0L)
  private val droppedUnreported = new AtomicLong(0L)
  private val running = new AtomicBoolean(true)
  private val out = new BufferedWriter(
    new OutputStreamWriter(stream, StandardCharsets.UTF_8),
    1 << 16
  )

  private val worker = {
    val t = new Thread(() => run(), "scribe-stdout-writer")
    t.setDaemon(true)
    t.start()
    t
  }

  /** Total lines dropped because stdout couldn't keep up; export this as a
    * metric.
    */
  def dropped: Long = droppedTotal.get()

  override def write(
      record: LogRecord,
      output: LogOutput,
      outputFormat: OutputFormat
  ): Unit = {
    val important = record.levelValue >= Level.Warn.value
    if (!important && pending.get() >= shedAbove) drop()
    else {
      // ASCII output is just the plain text (for JSON lines: the finished string), so skip the extra copy
      val line =
        if (outputFormat eq ASCIIOutputFormat) output.plainText
        else {
          val sb = new java.lang.StringBuilder(256)
          outputFormat(output, s => sb.append(s): Unit)
          sb.toString
        }
      val queued = queue.offer(line) || (important && queue.offer(
        line,
        importantWaitMillis,
        TimeUnit.MILLISECONDS
      ))
      if (queued) pending.incrementAndGet(): Unit else drop()
    }
  }

  private def drop(): Unit = {
    droppedTotal.incrementAndGet()
    droppedUnreported.incrementAndGet(): Unit
  }

  override def close(): Unit =
    if (running.compareAndSet(true, false)) worker.join(10000L)

  private def run(): Unit = {
    val batch = new java.util.ArrayList[String](1024)
    while (running.get() || !queue.isEmpty) {
      val first = queue.poll(100L, TimeUnit.MILLISECONDS)
      if (first != null) {
        batch.add(first)
        queue.drainTo(batch, 1023): Unit
        pending.addAndGet(-batch.size): Unit
        try {
          batch.forEach { line =>
            out.write(line)
            out.write('\n')
          }
          if (queue.isEmpty) out.flush()
        } catch {
          case NonFatal(t) => t.printStackTrace()
        } // a broken stdout must not kill the worker
        batch.clear()
      }
      val lost = droppedUnreported.getAndSet(0L)
      if (lost > 0L)
        scribe.warn(
          s"stdout can't keep up: dropped $lost log lines (INFO and below first)"
        )
    }
    try out.flush()
    catch { case NonFatal(t) => t.printStackTrace() }
  }
}
