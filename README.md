# LogPlay JVM Client SDK

A lightweight, framework-free JVM client for the LogPlay durable execution server. Written in Kotlin, compiled to Java 11 bytecode, and first-class callable from both Kotlin and Java.

## Modules

- **`logplay-client-core`** — pure module containing models, exceptions, codec interfaces, retry policies, and the `JobHandler` / `JobContext` Java interfaces. Only depends on Kotlin stdlib + SLF4J. Suitable for Android-only or native ports later.
- **`logplay-client`** — JVM implementation. Adds the JDK 11 `HttpClient`-based transport, Jackson wire serialization, the `Worker` runtime (heartbeat + acquire loops + bounded executor), the durable `JobContext` runtime, and `LogPlayManager` — the entry point.

## Requirements

- JDK 11 or later. The SDK ships Java 11 bytecode (verified by the `verifyJvmTarget` task wired into `check`).
- A reachable LogPlay server.

Building from source uses the Gradle wrapper, which auto-provisions any JDK toolchain it needs via Foojay — contributors don't need to install anything beyond a JDK that Gradle 9.x supports running on (JDK 17–26 as of this writing).

## Quickstart

### Kotlin

```kotlin
// groupId is committed once at the manager level (Kafka-consumer style).
val manager = LogPlayManager.builder()
    .baseUrl("http://localhost:8080")
    .groupId("emails")
    .build()

// 1. Submit a job. CreateJobRequest is generic over its input type; the factory chooses the
//    encoding strategy: Class<I> / TypeToken<I> for JSON via the manager's mapper, PayloadCodec<I>
//    for custom encoding, or builder() with no args for jobs that have no input.
val request = CreateJobRequest.builder(Codecs.UTF8)
    .type("send-welcome")
    .idempotencyKey("user-42-welcome")
    .input("user@example.com")
    .build()
val job = manager.createJob(request)

// 2. Run a worker that picks up jobs of that type. The reified registerHandler<I, O> uses JSON
//    via the manager's mapper; the codec/Class/TypeToken-form Java overloads are also available.
val worker = manager.newWorker("worker-1")
worker.registerHandler(
    type = "send-welcome",
    inputCodec = Codecs.UTF8,
    outputCodec = Codecs.UTF8,
    handler = JobHandler<String, String> { email, ctx ->
        // input/output may be null (handler signature is @Nullable). Side-effecting steps go
        // through ctx.run so they're checkpointed and skipped on replay.
        val token = ctx.run("issue-token", String::class.java) { generateToken(email!!) }
        ctx.runVoid("send-mail") { mailer.send(email!!, token!!) }
        "sent:$email"
    },
)
worker.start()
// ... later
worker.stop()
```

### Java

```java
LogPlayManager manager = LogPlayManager.builder()
        .baseUrl("http://localhost:8080")
        .groupId("emails")
        .build();

CreateJobRequest<String> req = CreateJobRequest.builder(Codecs.UTF8)
        .type("send-welcome")
        .idempotencyKey("user-42-welcome")
        .input("user@example.com")
        .build();
Job job = manager.createJob(req);

Worker worker = manager.newWorker("worker-1")
        .registerHandler(
                "send-welcome",
                Codecs.UTF8,
                Codecs.UTF8,
                (String email, JobContext ctx) -> {
                    String token = ctx.run("issue-token", String.class, () -> generateToken(email));
                    ctx.runVoid("send-mail", () -> mailer.send(email, token));
                    return "sent:" + email;
                });
worker.start();
```

### JSON-typed handler (typical case)

```java
LogPlayManager manager = LogPlayManager.builder()
        .baseUrl("http://localhost:8080")
        .groupId("orders")
        .build();

// JSON-encoded input via Class<I>; no manual codec or mapper wiring.
CreateJobRequest<Order> req = CreateJobRequest.builder(Order.class)
        .type("processOrder")
        .idempotencyKey("ord-42")
        .input(new Order("ord-42", List.of("a", "b"), 1500))
        .build();
manager.createJob(req);

manager.newWorker("worker-1")
        .registerHandler("processOrder", Order.class, Receipt.class,
                (order, ctx) -> new Receipt(order.id(), order.items().size(), order.total()))
        .start();
```

### DI-scoped handler (factory form)

```java
// Per-invocation handler instance — useful when handlers are managed beans whose dependencies
// vary per job, or when handlers carry per-job mutable state.
worker.registerHandler("processOrder", Order.class, Receipt.class,
        () -> applicationContext.getBean(MyOrderHandler.class));
```

## Building

```bash
./gradlew build                       # unit tests + Java-compat test (no server needed)
./gradlew :logplay-client:integrationTest   # integration tests (requires a running server)
./gradlew spotlessApply               # format
```

The integration test task surfaces server connection settings via:

- `-Plogplay.server.baseUrl=http://host:port` Gradle property
- `LOGPLAY_SERVER_BASE_URL=http://host:port` env var
- defaults to `http://localhost:8080`

If the server is unreachable, integration tests abort with a clear setup error explaining how to start it.

## Public API surface (high level)

| Type | Purpose |
|---|---|
| `LogPlayManager` | Entry point; built via `LogPlayManager.builder()` with required `baseUrl` + `groupId`. Blocking job CRUD. Worker factory. `jsonCodec(Class<T>)` / `jsonCodec(TypeToken<T>)` for ad-hoc codec construction (mix-and-match on `Worker.registerHandler`). |
| `CreateJobRequest<I>` | Generic immutable request. Factory variants — `builder(Class<I>)`, `builder(TypeToken<I>)`, `builder(PayloadCodec<I>)`, and `builder()` (returns `Builder<Nothing>` for no-input requests; `inputData(bytes)` extension is the raw-bytes escape hatch on this variant only). |
| `Worker` | Inherits `groupId` from the manager. Dispatches jobs of registered types to your handlers. `registerHandler(...)` has 6 overloads — codec/Class/TypeToken × single-instance `JobHandler` / per-invocation `HandlerFactory`. `start()` / `stop(graceful)`. |
| `WorkerConfig` | Tunable timeouts, pool size, page sizes, acquire strategy. |
| `JobHandler<I, O>` | Java functional interface — `@Nullable I` in, `@Nullable O` out. Your business logic. |
| `HandlerFactory<I, O>` | `JobHandler<I, O> create()`. Per-invocation factory for DI-scoped or stateful handlers. |
| `JobContext` | Passed into the handler. `ctx.run(name, type, block)` — durable, replay-aware step. Name is debug metadata; replay determinism is position-based. Both block input and return may be null. |
| `PayloadCodec<T>` | `encode(T?): ByteArray?` / `decode(ByteArray?): T?` — null-aware on both sides. Built-ins in `Codecs`; JSON via `JacksonPayloadCodec`. |
| `Codecs` | `BYTES`, `UTF8`, `UNIT` — common built-in codecs. |
| `RetryPolicy` / `ExponentialBackoffRetryPolicy` | Tunable retry for transport-level failures. |

Suspend extensions (`createJobAwait`, `abortJobAwait`, …) and reified Kotlin extensions (`Worker.registerHandler<I, O>`, `Worker.registerHandlerFactory<I, O>`, `LogPlayManager.jsonCodec<T>`) are available when `kotlinx-coroutines-core` is on the consumer's classpath; Java callers never see them.

## Documentation

- [`docs/Design.md`](docs/Design.md) — architectural overview, durable runtime semantics, threading model.
- [`CLAUDE.md`](CLAUDE.md) — guidance for Claude Code when working on this repo.

## License

[Apache License 2.0](LICENSE).
