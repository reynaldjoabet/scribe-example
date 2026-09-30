# scribe-example
This is what happens when your code calls `log.info("payment approved", data("orderId", "ord-1"))` during an HTTP request:
```sh
HTTP request arrives
   │
   ▼
RequestContext ── puts requestId/method/path into ──► LogContext (per-fiber storage)
   │
   ▼
PaymentService calls log.info(...)
   │
   ▼
Log ── is INFO enabled? no → do nothing, cost ≈ 0
   │   yes → build the record + attach everything from LogContext
   ▼
Scribe's Logger (level filters, the handler set up by LoggingSetup)
   │
   ▼
JsonLogFormat ── turns the record into one JSON line
   │
   ▼
AsyncStdoutWriter ── queues the line; a background thread writes it to stdout
   │
   ▼
stdout → your log collector (Datadog, Loki, ELK, CloudWatch…)
```

```json
{
    "level":"INFO","levelValue":300.0,"message":"charging order","fileName":"PaymentService.scala","className":"app.payments.PaymentService","methodName":"chargeOrder","line":33,"data":{"method":"POST","path":"/orders/ord-1/charge","orderId":"ord-1","customerId":"cust-ord-1","amountCents":4748,"currency":"USD","requestId":"abc","card":"CardToken(****4242)","itemCount":2},"trace":null,"timeStamp":1790789476859,"date":"2026-09-30","time":"21:31:16.859+0400"
}

{
    "level":"INFO","levelValue":300.0,"message":"payment approved","fileName":"PaymentService.scala","className":"app.payments.PaymentService","methodName":"chargeOrder","line":48,"data":{"method":"POST","path":"/orders/ord-1/charge","orderId":"ord-1","customerId":"cust-ord-1","amountCents":4748,"durationMs":49,"currency":"USD","requestId":"abc","card":"CardToken(****4242)","itemCount":2,"transactionId":"tx-8aeb3a7c-be3f-46a9-af56-2c6159be4476"},"trace":null,"timeStamp":1790789476978,"date":"2026-09-30","time":"21:31:16.978+0400"
}
```

## LogContext.scala: per-fiber storage for context

This holds `key/value` pairs such as `requestId`, `userId` and `traceId` that should appear on every log line within one operation, so you don't pass them to every `log.info` call yourself.

Scribe's built-in MDC stores these on the thread. cats-effect runs your code as fibers that move between threads, so thread-based context either gets lost or ends up on another request's logs. `LogContext` uses `IOLocal`, which belongs to the fiber, so the context follows your code wherever it runs. `ctx.scoped("requestId" -> id)(work)` sets the values for the duration of `work` and restores the previous ones afterwards.

`RequestContext.scala`: fills in `LogContext` for each HTTP request
This is http4s middleware wrapped around your routes. For each request it:
- takes `X-Request-ID` from the incoming headers, or generates one
- puts `requestId`, `method` and `path` into `LogContext` for that request
- echoes `X-Request-ID` back in the response, so a client can report an ID you can search for

That's why every line in the run output had `req=req-ord-1` and so on, even though `PaymentService` never mentions a request ID. It's only needed if you use http4s. For Kafka consumers or background jobs you'd call c`tx.scoped(...)` yourself in the same way

## AsyncStdoutWriter.scala: gets lines to stdout without slowing your app
It receives the finished JSON line and writes it out:

- The JSON is built on your fiber's thread. That's CPU work, so it spreads across all cores and belongs there.
- Writing to stdout happens on one background thread. That's blocking I/O, which shouldn't run on cats-effect's compute threads. If stdout slows down, for example because the log collector is backed up, the queue fills rather than your request threads stalling.
- If the queue is completely full (`65,536` lines), new lines are dropped and counted. Losing a log line is better than hanging the whole service. LoggingSetup.dropped gives you the count to export as a metric.
- It writes through a 64 KB buffer and flushes when the queue is empty, so it makes a few large writes rather than one system call per line.

Why JSON at all: collectors like Datadog, Loki, ELK and CloudWatch parse each JSON line into searchable fields. With plain text you can only search free text, so a query like "all errors for order ord-4" becomes guesswork.

## Running
```sh
sbt --client "catsApp/run"     # :8080
sbt --client "zioApp/run"      # :8081
sbt --client test              # all 16 tests
curl -X POST -H "X-Request-ID: abc" localhost:8081/orders/ord-1/charge
```

## The idea: SLF4J is an interface

SLF4J ("Simple Logging Facade for Java") separates two things:
- `the API that code calls`: `org.slf4j.Logger`, `LoggerFactory`, `MDC`
- the implementation that actually writes logs, called a provider (in SLF4J 1.7 it was called a "binding")
Libraries compile against the API only. The application decides at runtime which implementation receives the logs

```sh
  COMPILE TIME: each library only knows the interface
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │   HikariCP   │  │ Kafka client │  │    Netty     │  │log4cats-slf4j│
  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
         │ LoggerFactory.getLogger(...).info("...")            │
         └─────────────────┴────────┬────────┴─────────────────┘
                                    ▼
                  ┌───────────────────────────────────┐
                  │     slf4j-api  (interfaces only)  │
                  │  Logger · LoggerFactory · MDC     │
                  └─────────────────┬─────────────────┘
  RUNTIME: exactly ONE provider     │  found on the classpath at startup
  is plugged in here                ▼
        ┌──────────────┬────────────┴─┬───────────────┬──────────────┐
        ▼              ▼              ▼               ▼              ▼
   scribe-slf4j2   logback-classic  log4j-slf4j2-impl  slf4j-simple   (none)
        │              │              │               │              │
        ▼              ▼              ▼               ▼              ▼
     Scribe         Logback        Log4j 2         stderr     NOP: everything
   (your JSON    (logback.xml)  (log4j2.xml)                  silently dropped
    pipeline)
```
This is why the rule is: libraries depend only on `slf4j-api`, and the application adds exactly one provider. If a library shipped a provider, it would force its choice on every app that used it


## Startup: how SLF4J 2.x finds a provider
This happens once, on the first `LoggerFactory.getLogger(...)` call anywhere in the JVM:

```sh
 first LoggerFactory.getLogger("com.zaxxer.hikari.HikariPool")
                    │
                    ▼
 ┌──────────────────────────────────────────────────────────────┐
 │ 1. Is the system property  -Dslf4j.provider=<class>  set?    │
 │    yes → load exactly that class, skip discovery             │
 └──────────────────────────────┬───────────────────────────────┘
                                │ no
                                ▼
 ┌──────────────────────────────────────────────────────────────┐
 │ 2. ServiceLoader: scan every jar for                         │
 │    META-INF/services/org.slf4j.spi.SLF4JServiceProvider      │
 │    (scribe-slf4j2's file names scribe.slf4j.ScribeServiceProvider)
 └──────────────────────────────┬───────────────────────────────┘
                                │
          ┌─────────────────────┼──────────────────────┐
          ▼                     ▼                      ▼
     0 found               1 found                2+ found
          │                     │                      │
          ▼                     │                      ▼
 stderr: "No SLF4J              │           stderr: "Class path contains
 providers were found."         │           multiple SLF4J providers"
 (+ "Ignoring binding           │           → uses the FIRST one found
 found at ..." if an old        │             (classpath order, not
 1.7 binding is present)        │              something you control well)
          │                     │                      │
          ▼                     ▼                      ▼
   NOP provider:        ┌─────────────────────────────────────┐
   every log call       │ 3. provider.initialize()            │
   does nothing         │ 4. keep its three factories:        │
                        │    ILoggerFactory → creates Loggers │
                        │    MDCAdapter     → backs MDC.put   │
                        │    IMarkerFactory → markers         │
                        └─────────────────────────────────────┘
```

How `SLF4J 1.7` differed: it had no ServiceLoader. `slf4j-api 1.7` called a class named `org.slf4j.impl.StaticLoggerBinder` directly, and each binding jar shipped its own copy of that class. The JVM loaded whichever copy came first on the classpath. `SLF4J 2` ignores that class entirely, which is why a `1.7` binding does nothing with `API 2.x`.

## Each log call, through Scribe's provider
This is what happens when Hikari logs a warning in your scribe-example app:

```sh
 HikariPool:  log.warn("Connection is not available, request timed out after {}ms", 30000)
      │
      ▼
 ScribeLoggerAdapter("com.zaxxer.hikari.pool.HikariPool")   ← one per logger name, cached
      │
      ├─ isWarnEnabled()? ──► scribe.Logger(name).includes(Warn)
      │                          (Scribe's level config, incl. Logger.minimumLevels
      │                           "com.zaxxer.hikari" -> Warn from LoggingSetup)
      │     no → return, nothing formatted
      │
      ├─ SLF4J's MessageFormatter fills {} placeholders
      │     → "Connection is not available, request timed out after 30000ms"
      │
      ▼
 SLF4JHelper.log  →  builds a Scribe LogRecord
      │                 level     = Warn
      │                 className = "com.zaxxer.hikari.pool.HikariPool" (the logger name)
      │                 message   = formatted text (+ stack trace if a Throwable was passed)
      ▼
 scribe.Logger(name).log(record)
      ▼
 JsonLogFormat (adds MDC values put via org.slf4j.MDC) → AsyncStdoutWriter → stdout
 ```

`org.slf4j.MDC` is stored on the thread, in Scribe's thread-local MDC. That's fine for libraries that set and read it on one thread. It doesn't follow cats-effect or ZIO fibers, which is why your code uses `LogContext` or `logAnnotate`

```sh
org.slf4j.LoggerFactory.getLogger(name) ──► provider.getLoggerFactory().getLogger(name)
                                              scribe: ScribeLoggerFactory → ScribeLoggerAdapter
org.slf4j.MDC.put(k, v)                 ──► provider.getMDCAdapter().put(k, v)
                                              scribe: ScribeMDCAdapter → scribe.mdc.MDC
org.slf4j.Logger (interface)            ◄── implemented by the provider's logger class
```

So an app ends up with several logging APIs at once, and without bridges each one writes somewhere different:

```sh
 WITHOUT BRIDGES: four outputs, four formats, four configs, four sets of levels

 Hikari ──────► SLF4J ────► Scribe ───────────► stdout (your JSON)
 pgjdbc ──────► JUL ──────► JUL ConsoleHandler ► stderr, two-line plain text, ignores LOG_LEVEL
 HttpClient 4 ► JCL ──────► picks something at runtime ► ??? 
 ES client ───► Log4j 2 API ► no config found ─► "StatusLogger: No Log4j 2 configuration file found", drops logs

 WITH BRIDGES: everything ends up in one pipeline

 Hikari ──────► SLF4J ──────────────────┐
 pgjdbc ──────► JUL ─── jul-to-slf4j ───┤
 HttpClient 4 ► JCL ─── jcl-over-slf4j ─┼──► SLF4J ──► ONE provider ──► one format, one config,
 ES client ───► Log4j2  ─log4j-to-slf4j ┘                              one set of levels
 ```

 The benefit is one place to set levels, one JSON format and one output. Without it, a database driver error goes to stderr in a different format, your log collector can't parse it, and `LOG_LEVEL` doesn't affect it

