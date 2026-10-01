package app.logging

import scribe.output.LogOutput
import scribe.output.format.{ASCIIOutputFormat, OutputFormat}
import scribe.writer.Writer
import scribe.{Level, LogRecord, Logger}

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/** Shared by CatsProductionBenchmark and ZioProductionBenchmark, so both
  * measure the same workload the same way and write results bench/report.py can
  * compare.
  *
  * {{{
  * Workload (scenario "requests"): Concurrency fibers each handle requests like an HTTP handler at INFO level:
  *   INFO "request received" -> 2 disabled DEBUG -> 1ms "DB call" (fiber usually resumes on another thread)
  *   -> 1 disabled DEBUG -> circe response encoding -> 1 disabled DEBUG
  *   -> INFO "order charged" (97.5%) | WARN "payment declined" (2%) | ERROR with stack trace (0.5%)
  * Request context (requestId, method, path) is set once per request by each runtime's own mechanism.
  * }}}
  */
object BenchSupport {
  val Concurrency = 256
  val WarmupRequests = 60000
  val MeasuredRequests = 300000
  val DebugWarmup = 2000000
  val DebugCalls = 5000000

  /** Command line: <logging|baseline> <requests|debug-hot> [verify] [--json
    * <file>]
    */
  final case class Args(
      logging: Boolean,
      scenario: String,
      verify: Boolean,
      jsonOut: Option[String]
  )

  def parseArgs(args: Array[String]): Args = {
    val logging = args(0) match {
      case "logging"  => true
      case "baseline" => false
      case other      =>
        sys.error(
          s"First argument must be 'logging' or 'baseline', got: $other"
        )
    }
    val jsonOut = args.indexOf("--json") match {
      case -1 => None
      case i  => args.lift(i + 1)
    }
    Args(logging, args(1), args.contains("verify"), jsonOut)
  }

  /** Same output pipeline as ScribeLogging (JsonFormatter + AsyncStdoutWriter,
    * INFO level), or CheckingWriter.
    */
  def setupScribe(verify: Boolean): AsyncStdoutWriter = {
    val stdout = new AsyncStdoutWriter(65536)
    Logger.root
      .clearHandlers()
      .clearModifiers()
      .withMinimumLevel(Level.Info)
      .withHandler(
        formatter = JsonFormatter,
        writer = if (verify) CheckingWriter else stdout,
        outputFormat = ASCIIOutputFormat
      )
      .replace(): Unit
    stdout
  }

  /** Verify mode: every log call also carries "expected" = its request's id;
    * this checks the requestId the line would get in its JSON matches it.
    */
  object CheckingWriter extends Writer {
    val checked = new LongAdder
    val missing = new LongAdder
    val wrong = new LongAdder
    override def write(
        record: LogRecord,
        output: LogOutput,
        outputFormat: OutputFormat
    ): Unit = {
      val fields = JsonLogFields.data(record)
      fields.get("expected").map(_()).foreach { expected =>
        checked.increment()
        fields.get("requestId").map(_()) match {
          case None                               => missing.increment()
          case Some(got) if !got.equals(expected) => wrong.increment()
          case _                                  => ()
        }
      }
    }
  }

  /** An error with a realistic ~30-frame stack trace and a cause. */
  val gatewayFailure: Throwable = {
    def deep(n: Int): Throwable =
      if (n == 0)
        new IllegalStateException(
          "gateway failed",
          new java.net.SocketTimeoutException("Read timed out")
        )
      else deep(n - 1)
    deep(25)
  }

  final case class Counters(
      cpuNanos: Long,
      allocated: Long,
      gcCount: Long,
      gcMillis: Long
  )

  def counters(): Counters = {
    val threads = ManagementFactory.getThreadMXBean
      .asInstanceOf[com.sun.management.ThreadMXBean]
    val os = ManagementFactory.getOperatingSystemMXBean
      .asInstanceOf[com.sun.management.OperatingSystemMXBean]
    val gcs = ManagementFactory.getGarbageCollectorMXBeans.asScala
    Counters(
      os.getProcessCpuTime,
      threads.getTotalThreadAllocatedBytes,
      gcs.map(_.getCollectionCount).sum,
      gcs.map(_.getCollectionTime).sum
    )
  }

  def allocatedBytes(): Long =
    ManagementFactory.getThreadMXBean
      .asInstanceOf[com.sun.management.ThreadMXBean]
      .getTotalThreadAllocatedBytes

  /** Prints and records the "requests" scenario. `latencies` are nanoseconds
    * per request.
    */
  def reportRequests(
      variant: String,
      args: Args,
      latencies: Array[Long],
      elapsedNanos: Long,
      before: Counters,
      after: Counters,
      dropped: Long
  ): Unit = {
    val n = latencies.length
    java.util.Arrays.sort(latencies)
    def pct(p: Double): Double = latencies(math.min(n - 1, (p * n).toInt)) / 1e6
    val seconds = elapsedNanos / 1e9
    val metrics = List(
      "reqPerSec" -> n / seconds,
      "p50Ms" -> pct(0.50),
      "p99Ms" -> pct(0.99),
      "p999Ms" -> pct(0.999),
      "cpuUsPerReq" -> (after.cpuNanos - before.cpuNanos) / 1e3 / n,
      "allocKbPerReq" -> (after.allocated - before.allocated) / 1024.0 / n,
      "gcCount" -> (after.gcCount - before.gcCount).toDouble,
      "gcMs" -> (after.gcMillis - before.gcMillis).toDouble,
      "dropped" -> dropped.toDouble
    )
    appendJson(args.jsonOut, "requests", variant, metrics*)
    val m = metrics.toMap
    System.err.println(
      f"$variant%-13s ${m("reqPerSec")}%,8.0f req/s  p50 ${m("p50Ms")}%5.2f ms  p99 ${m("p99Ms")}%5.2f ms  " +
        f"p999 ${m("p999Ms")}%5.2f ms  CPU ${m("cpuUsPerReq")}%5.1f µs/req  alloc ${m("allocKbPerReq")}%5.1f KB/req  " +
        f"GC ${m("gcCount")}%3.0f (${m("gcMs")}%4.0f ms)  dropped $dropped"
    )
  }

  def reportVerify(variant: String, args: Args): Unit = {
    val (checked, missing, wrong) = (
      CheckingWriter.checked.sum(),
      CheckingWriter.missing.sum(),
      CheckingWriter.wrong.sum()
    )
    appendJson(
      args.jsonOut,
      "verify",
      variant,
      "checked" -> checked.toDouble,
      "missing" -> missing.toDouble,
      "wrong" -> wrong.toDouble
    )
    System.err.println(
      f"$variant%-13s verify: $checked%,d lines checked, $missing%,d missing requestId, $wrong%,d wrong requestId"
    )
  }

  def reportDebugHot(
      variant: String,
      args: Args,
      elapsedNanos: Long,
      allocated: Long
  ): Unit = {
    val ns = elapsedNanos.toDouble / DebugCalls
    val bytes = allocated.toDouble / DebugCalls
    appendJson(
      args.jsonOut,
      "debug-hot",
      variant,
      "nsPerCall" -> ns,
      "bytesPerCall" -> bytes
    )
    System.err.println(
      f"$variant%-13s disabled debug: $ns%6.1f ns/call  $bytes%6.1f bytes/call"
    )
  }

  /** One JSON line per run:
    * {"scenario":..,"variant":..,"cores":..,"java":..,"metrics":{..}}.
    */
  private def appendJson(
      path: Option[String],
      scenario: String,
      variant: String,
      metrics: (String, Double)*
  ): Unit =
    path.foreach { p =>
      val fields = metrics.map((k, v) => s""""$k":$v""").mkString(",")
      val line = s"""{"scenario":"$scenario","variant":"$variant",""" +
        s""""cores":${Runtime.getRuntime.availableProcessors},"java":"${System
            .getProperty("java.version")}",""" +
        s""""metrics":{$fields}}\n"""
      Files.writeString(
        Paths.get(p),
        line,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      ): Unit
    }
}
