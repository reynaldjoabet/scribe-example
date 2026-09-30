package app.logging

import scribe.LogRecord
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer

import java.io.{BufferedWriter, FileDescriptor, FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.util.control.NonFatal

/**
 * Stdout writer that splits CPU work from blocking I/O:
 *  - Formatting (JSON rendering, etc.) runs on the calling thread, so it scales across all cores. That's CPU-bound
 *    work, which is fine on the cats-effect compute pool.
 *  - Only finished lines are queued. One worker thread writes them through a 64KB buffer to the stdout file
 *    descriptor, flushing whenever the queue empties (large writes under load, low latency when quiet).
 *  - Callers never block: when the queue is full the line is dropped and counted, so a stalled stdout can't freeze
 *    the compute pool.
 *  - `close()` drains everything queued, then flushes.
 *
 * Replaces Scribe's AsynchronousLogHandle, whose worker sleeps 1ms per record (~1000 records/sec) and drops the rest.
 * Use it with the default SynchronousLogHandle.
 */
final class AsyncStdoutWriter(capacity: Int = 65536) extends Writer with AutoCloseable {
  private val queue = new ArrayBlockingQueue[String](capacity)
  private val droppedTotal = new AtomicLong(0L)
  private val droppedUnreported = new AtomicLong(0L)
  private val running = new AtomicBoolean(true)
  private val out = new BufferedWriter(
    new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8),
    1 << 16
  )

  private val worker = {
    val t = new Thread(() => run(), "scribe-stdout-writer")
    t.setDaemon(true)
    t.start()
    t
  }

  /** Total lines dropped because the queue was full; export this as a metric. */
  def dropped: Long = droppedTotal.get()

  override def write(record: LogRecord, output: LogOutput, outputFormat: OutputFormat): Unit = {
    val sb = new java.lang.StringBuilder(256)
    outputFormat(output, s => sb.append(s): Unit)
    if (!queue.offer(sb.toString)) {
      droppedTotal.incrementAndGet()
      droppedUnreported.incrementAndGet(): Unit
    }
  }

  override def close(): Unit = if (running.compareAndSet(true, false)) worker.join(10000L)

  private def run(): Unit = {
    val batch = new java.util.ArrayList[String](1024)
    while (running.get() || !queue.isEmpty) {
      val first = queue.poll(100L, TimeUnit.MILLISECONDS)
      if (first != null) {
        batch.add(first)
        queue.drainTo(batch, 1023)
        try {
          batch.forEach { line =>
            out.write(line)
            out.write('\n')
          }
          if (queue.isEmpty) out.flush()
        } catch { case NonFatal(t) => t.printStackTrace() } // a broken stdout must not kill the worker
        batch.clear()
      }
      val lost = droppedUnreported.getAndSet(0L)
      if (lost > 0L) scribe.warn(s"Log queue full (capacity $capacity): dropped $lost lines")
    }
    try out.flush()
    catch { case NonFatal(t) => t.printStackTrace() }
  }
}
