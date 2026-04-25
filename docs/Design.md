# Design — LogPlay JVM Client SDK

This document describes the SDK's architecture, the durable execution model it exposes to user code, and the threading and lifecycle guarantees it gives.

## Goals

1. **Lightweight.** No framework dependency. Zero coroutine cost for Java users. JDK 11 baseline.
2. **First-class Java.** Every public API works as cleanly from Java as from Kotlin. Java functional-interface lambdas. Builders for required-field DTOs. No Kotlin-only types in the public surface.
3. **Three responsibilities, one entry point.** A single `LogPlayManager` exposes (a) job CRUD, (b) a worker runtime that polls and executes jobs, and (c) a durable execution context for handler bodies.
4. **Pluggable serialization.** Built-in Jackson-based codec; user can swap to Protobuf, kotlinx-serialization, raw bytes, etc.
5. **Replayable handlers.** Side-effecting steps are wrapped in `ctx.run(name, …)`; on resume, those steps return their previously-recorded result instead of re-executing.

## Module layout

Two Gradle modules:

- **`logplay-client-core`** — pure module. No HTTP, no Jackson. Holds models, exceptions, codec interfaces, retry primitives, and the `JobHandler` / `JobContext` Java interfaces. A non-JVM port (Android-only, native) would only need to re-implement `logplay-client`.
- **`logplay-client`** — JVM implementation. Adds the JDK 11 `HttpClient`-based transport, Jackson wire serialization, the worker runtime, and the durable execution runtime.

Public package: `org.zeplinko.logplay.client.*`. Internal-only types are marked Kotlin `internal` so they're not part of the consumer API even at bytecode level.

## Public entry point — `LogPlayManager`

Constructed via a builder:

```
LogPlayManager.builder()
    .baseUrl("http://localhost:8080")  // required
    .groupId("emails")                 // required — service-wide consumer group (Kafka-style)
    .httpTimeout(Duration.ofSeconds(30))
    .objectMapper(myMapper)            // optional; KotlinModule + JavaTimeModule auto-registered
    .defaultRetryPolicy(myPolicy)      // optional; default exponential backoff
    .headers(Map.of("Authorization", "Bearer ..."))
    .userAgent("my-app/1.2.3")
    .build();
```

`groupId` is committed once at the manager level — both produce-side (`createJob`) and consume-side (workers) inherit it. For the rare cross-group submit, `manager.createJob(req, groupIdOverride)` accepts an explicit override.

The manager owns:
- A shared `java.net.http.HttpClient` (HTTP/2 by default; JDK 11 has no `close()` so we don't pretend to).
- A shared `ObjectMapper` (force-registers `KotlinModule` and `JavaTimeModule` even if the caller provides their own — both are idempotent). This is the mapper that backs `JacksonPayloadCodec` instances minted by `manager.jsonCodec(...)` and by the `Class<T>`/`TypeToken<T>` overloads on `Worker.registerHandler` and `CreateJobRequest.builder`.
- A `defaultRetryPolicy` used by the internal `RetryExecutor` for retryable failures inside the worker loop.

It exposes:

| Group | Methods |
|---|---|
| Job CRUD | `createJob(req)`, `createJob(req, groupIdOverride)`, `abortJob(id)`, `getCheckpoints(jobId, after?, limit?)`, `getEvents(jobId)` — all blocking |
| Worker factory | `newWorker(workerId)` and `newWorker(workerId, WorkerConfig)` |
| Codec primitives | `jsonCodec(Class<T>)`, `jsonCodec(TypeToken<T>)` — produce a `PayloadCodec<T>` backed by the manager's `ObjectMapper` for mix-and-match cases on `Worker.registerHandler` |

All manager methods are blocking. The SDK holds no callback thread pool — wrap calls in your own executor or coroutine context (Kotlin users can use the `…Await` suspend extensions, which offload to `Dispatchers.IO`) when you need asynchrony.

## Worker — polling and dispatch

A `Worker` inherits its `groupId` from the manager and holds a registry of (`type` → handler) bindings. One Worker can handle many job types within the manager's group; for multiple groups, build multiple `LogPlayManager` instances (each owns its own `groupId`).

```
Worker w = manager.newWorker("worker-1")
    .registerHandler("welcome", Codecs.UTF8, Codecs.UTF8, (input, ctx) -> { ... })
    .registerHandler("reminder", String.class, String.class, (input, ctx) -> { ... });
w.start();
// ...
w.stop(true);  // graceful: drain in-flight, then DELETE worker server-side
```

`Worker.registerHandler` has 6 overloads, the cross-product of:

- **Codec source**: `(PayloadCodec<I>, PayloadCodec<O>)`, `(Class<I>, Class<O>)`, `(TypeToken<I>, TypeToken<O>)`. The Class/TypeToken forms construct `JacksonPayloadCodec` from the manager's `ObjectMapper`. For mix-and-match (e.g., JSON input + custom output codec), use `manager.jsonCodec(...)` to bridge.
- **Handler shape**: `JobHandler<I, O>` (single shared instance, must be thread-safe — zero per-invocation allocation) or `HandlerFactory<I, O>` (`JobHandler<I, O> create()` invoked once per job — for DI-scoped or per-invocation-stateful handlers).

### Threading model

A worker owns three thread groups:

1. **Acquire loop** — one dedicated daemon thread (`logplay-worker-${id}-acquire`).
2. **Heartbeat loop** — a single-threaded `ScheduledExecutorService` (`logplay-worker-${id}-heartbeat-N`) firing every `heartbeatIntervalMs` (default = `heartbeatTimeoutMs / 3`).
3. **Handler executor** — fixed-size `ThreadPoolExecutor` (`logplay-worker-${id}-handler-N`), default 4 threads. Bounded by a `Semaphore` of the same size (used as the source of truth for "free slots" because `ThreadPoolExecutor.getActiveCount()` is approximate).

### Acquire loop algorithm

Each iteration:

1. Read free slots from the semaphore. If 0, sleep ~50 ms and retry.
2. If the handler registry is empty, sleep `idleBackoff` and retry.
3. Snapshot registered types and ask the configured `AcquireStrategy` for a plan: a list of `(type, limit)` requests summing to ≤ free slots.
4. For each `(type, limit)`: call `POST /jobs/acquire` once for that type. The server's acquire endpoint takes a single type per call, so N registered types = up to N HTTP calls per iteration.
5. For each acquired job: reserve a semaphore permit and submit `runJob(job)` to the handler executor.
6. If nothing was acquired this iteration, double `idleBackoff` (capped at `acquireMaxBackoffMs`). Otherwise reset to initial.

The default strategy is `RoundRobinAcquireStrategy`: distributes free slots evenly across types and rotates the start index per iteration to prevent starvation. The `AcquireStrategy` interface is public so additional strategies can be plugged in without an API break.

### Per-job execution

For each acquired job, on a handler-executor thread:

```
1. Look up the handler registration for `job.type`.
2. Decode `job.inputData` via the registered input codec (codec accepts null — wire null produces a null handler input).
3. Fetch the first page of checkpoints; build a `CheckpointReplayState` (lazy-paginated).
4. Build a `JobContextImpl` and invoke `registration.factory.create().execute(input, ctx)` — fresh handler per job for the factory form, or the captured singleton for the JobHandler form (zero allocation in the latter).
5. Encode the returned output via the registered output codec (codec returns nullable bytes — null handler output produces no outputData on the wire).
6. Retry-wrap a `complete` call so transient transport errors are handled.

On `InterruptedException`: release the job (server moves it back to PENDING).
On any other Throwable: report-error with the truncated message; the server decides retry vs FAILED.
Always release the semaphore permit in a `finally`.
```

### Heartbeat loop

A daemon `ScheduledExecutorService` calls `POST /workers/:id/heartbeat` at the configured interval. On `WorkerCondemnedException` or `WorkerNotFoundException` from the server, it triggers an asynchronous shutdown so the worker stops accepting work and deregisters cleanly. Other transient errors are logged at WARN and retried on the next tick.

### Lifecycle

`start()` registers the worker with the server (`POST /workers`), starts both loops, and atomically transitions to `RUNNING`. It's safe to call only once per Worker instance.

`stop(graceful = true)` — stops new acquires, joins the acquire thread within `shutdownGraceMs`, drains the handler executor, cancels heartbeats, and `DELETE`s the worker server-side (which the server uses to release any still-acquired jobs back to `PENDING`).

`stop(graceful = false)` — same flow, but `shutdownNow()` instead of `awaitTermination`. Use for tests or hard-stop scenarios.

A graceful stop also fires on the heartbeat loop if the server condemns or forgets the worker, so the SDK self-corrects.

## Durable runtime — `JobContext.run`

The handler is a plain method. Side-effecting work that must survive worker crashes / retries goes through `ctx.run`:

```kotlin
val token = ctx.run("issue-token", String::class.java) { issueToken(email) }
ctx.runVoid("send-mail") { mailer.send(email, token) }
```

On the **first** invocation:
- `ctx.run` executes `block`, encodes the result via the codec, persists a checkpoint via `POST /jobs/:id/checkpoints`, and returns the result.

On a **replay** (worker re-acquires the same job after a restart or error):
- The handler is invoked from the top, but each `ctx.run(name, …)` looks up the next checkpoint by position. If one exists, its data is decoded via the codec and returned **without re-executing the block**. Side effects are therefore exactly-once with respect to checkpoints.

### Replay state — lazy pagination

`CheckpointReplayState` keeps a cursor over a list of `Checkpoint`s. The first page is fetched eagerly when the handler is dispatched (default 50 per page, configurable via `WorkerConfig.checkpointPageSize`). When the cursor advances past the loaded set and the server reports `hasMore`, the next page is fetched on demand using the last loaded checkpoint's `id` as the cursor.

This keeps memory bounded for long-running jobs with many checkpoints — the SDK never loads the entire chain unless your handler asks for that many steps in a single execution.

### Divergence detection

Replay determinism is **position-based**, not name-based. If a handler is changed between runs and the next `ctx.run` call has a different `name` than the next persisted checkpoint, the SDK logs a warning and continues — the persisted bytes at that position are decoded through the user-supplied codec. If the user changed the *type* at the same position (e.g., position 0 used to checkpoint a `String`, now expects an `Int`), the codec's `decode` will fail with a Jackson-level error, surfacing the divergence naturally.

`CheckpointDivergenceException` exists in the exception hierarchy but is **currently unthrown**. It's reserved for the future case where the journal grows non-`run` operation kinds (sleeps, awakeables, RPC calls) and a position-N kind mismatch becomes a real divergence signal.

### Chain-ahead recovery

If a `saveCheckpoint` call returns `409 InvalidCheckpointOrderException`, another worker (or our own previous attempt) wrote past us. `CheckpointSaver` re-fetches the next checkpoint after our last known id; if that checkpoint has the same `name` we were about to write, it's idempotent (the server's checkpoint IDs are deterministic), so we adopt the server's value and proceed. Otherwise — the server has a checkpoint at the same position with a different name — that's still treated as divergence at write time and `CheckpointDivergenceException` is thrown from `CheckpointSaver` (the only currently-live throw site).

### Checkpoint names are debug metadata

The `name` parameter on `ctx.run(name, ...)` is **observability only** — it appears in logs, traces, and persisted checkpoint metadata. It is **not** part of the determinism contract:

- **Renaming a step across deploys is safe.** Replay matches by position; the codec decodes the persisted bytes regardless of the new name.
- **Reusing a name within a single execution is safe.** Loops calling `ctx.run("step-${i}", ...)` are still idiomatic for log/trace clarity, but accidental name collisions only produce a warning log.
- **Adding/removing/reordering `ctx.run` calls between executions DOES break determinism.** That's the user's responsibility — the SDK can't tell what the user "intended" at a given position, only what bytes are stored there.

Blank names are still rejected at runtime — they defeat the debug purpose entirely.

### Out of scope (v1)

- **Sleep / timer / awakable** APIs — the server doesn't have them yet.
- **Parallel branches** within a single job — `ctx.run` is strictly sequential. Fan-out parallelism would need an explicit `previousCheckpointId` argument.

## Codecs

`PayloadCodec<T>` is a tiny interface, **null-aware on both sides**:

```kotlin
interface PayloadCodec<T> {
    fun encode(value: T?): ByteArray?
    fun decode(bytes: ByteArray?): T?
}
```

The wire fields `inputData`, `outputData`, and checkpoint `data` are all nullable. By making the codec contract nullable on both sides, every encode/decode site in the SDK becomes a pure pass-through — there are no per-site `?.let { … }` guards. Built-in implementations follow the conventional pattern:

```kotlin
override fun encode(value: String?): ByteArray? = value?.toByteArray(Charsets.UTF_8)
override fun decode(bytes: ByteArray?): String? = bytes?.let { String(it, Charsets.UTF_8) }
```

Built-ins in `Codecs`: `BYTES` (identity), `UTF8` (string), `UNIT` (`Unit` ↔ empty bytes; null ↔ null). All pass null through unchanged.

The default codec for `ctx.run(name, Class<T>, …)` and `ctx.run(name, TypeToken<T>, …)` overloads is `JacksonPayloadCodec` (in `logplay-client`) — it skips Jackson entirely on null and returns null bytes (no JSON literal `null` shows up on the wire). Users wanting different serialization (Protobuf, kotlinx-serialization, …) can pass their own `PayloadCodec<T>` to the most general overload `ctx.run(name, PayloadCodec<T>, …)`.

`TypeToken<T>` is a tiny generic-capture helper in `core` (Jackson-free) so generic payloads like `List<MyDto>` can be deserialized correctly.

### Job creation — typed, factory-committed source

`CreateJobRequest<I>` is generic. The encoding strategy is committed at the builder factory:

| Factory | Builder type | Use case |
|---|---|---|
| `builder(Class<I>)` | `Builder<I>` | JSON input via the manager's `ObjectMapper` |
| `builder(TypeToken<I>)` | `Builder<I>` | JSON input with parameterized generics |
| `builder(PayloadCodec<I>)` | `Builder<I>` | Custom codec (Protobuf, msgpack, etc.) |
| `builder()` | `Builder<Nothing>` | No input (or pre-encoded raw bytes via the `inputData(bytes)` extension) |

After the factory, `.input(value)` is a plain setter — the type and codec are already bound. Internally a sealed `InputSource<I>` carries the discrimination; the manager dispatches on it inside `JobDtos.toDto(groupId, mapper)` to produce the wire payload.

## Wire layer

### HTTP transport

`JdkHttpTransport` wraps a single shared `HttpClient` (HTTP/2, configurable connect timeout). All request/response bodies pass through the manager's `ObjectMapper`. Per-request timeouts are set on the `HttpRequest`. On non-2xx responses, `ErrorMapper.translate(status, body)` produces a typed exception (see below) and the transport throws.

### DTO strategy

Wire DTOs (`*Dto` data classes in `dto/`) are Jackson-annotated and `internal` to the JVM module. Domain types in `core.model.*` stay Jackson-free.

- Dates: ISO-8601 via `Instant.toString()` / `Instant.parse(...)` with `JavaTimeModule`.
- Binary: standard `java.util.Base64` (not URL-safe).
- Null handling: `setSerializationInclusion(JsonInclude.Include.NON_NULL)` on the way out; lax on the way in (`FAIL_ON_UNKNOWN_PROPERTIES = false`).
- Unknown enum values from the server (forward compatibility risk): converted to `LogPlayServerException` so the SDK never silently swallows a future status.

Conversion between `*Dto` and domain types is a pair of extension functions per file (`toDto()`, `toDomain()`).

### Error mapping

`LogPlayClientException` is the open root. Below it:

- `LogPlayServerException(status, message)` — fallback when no specific subtype matches.
- `LogPlayTransportException` — IO/timeout. Implements `Retryable`.
- Category parents: `LogPlayValidationException` (400), `NotFoundException` (404), `ForbiddenException` (403), `ConflictException` (409).
- ~25 specific subtypes mirror the server's documented error situations (e.g., `BlankGroupIdException`, `JobNotFoundException`, `WorkerCondemnedException`, `DuplicateIdempotencyKeyException`, `JobConcurrentModificationException` (also `Retryable`), `InvalidCheckpointOrderException`, `WorkerAlreadyRegisteredException`, …).

`ErrorMapper` dispatches on HTTP status and matches on the server's error message text to pick a specific subtype. When the server's wording changes, update both `ErrorMapper` and `ErrorMapperTest`. Specific subtype matching is best-effort: when in doubt, callers should catch the category parent.

## Retry

`RetryPolicy` decides backoff or stop. Default `ExponentialBackoffRetryPolicy(maxAttempts, baseDelayMs, maxDelayMs, jitter)` uses full-jitter exponential backoff.

`RetryExecutor.run(opName, op)` only retries when the thrown exception implements `Retryable`. By design:

- `LogPlayTransportException` is `Retryable` — IO blips get retried.
- `JobConcurrentModificationException` is `Retryable` — optimistic-lock conflicts get a fresh attempt.
- Validation, not-found, forbidden, and most conflict errors are **not** retried — they reflect deterministic bad state.

The worker uses retry around `complete`, `release`, and `report-error` calls so transient HTTP errors don't lose work.

## Suspend extensions for Kotlin

A small `SuspendExtensions.kt` file adds `suspend fun LogPlayManager.createJobAwait(...)` etc., each offloading the blocking call to `Dispatchers.IO` via `withContext`. The coroutine dependency is `compileOnly` — it ships in the SDK's main source set but is **not** transitively pulled in. Java users incur zero coroutine cost; Kotlin users wanting these helpers add `kotlinx-coroutines-core` to their own project.

## Build conventions

- Kotlin 2.3, `jvmToolchain(11)`, target JVM 11 — pinned in `buildSrc/src/main/kotlin/kotlin-client-module-base.gradle.kts`. This is the bytecode that ships to consumers.
- `buildSrc` itself has no toolchain pin; Gradle's embedded Kotlin handles the build-script compilation. Contributors need only a JDK that Gradle 9.x supports running on (17–26); Gradle auto-provisions the JDK 11 toolchain via Foojay for the SDK compilation step.
- `explicitApiWarning()` — every public symbol has explicit visibility and return type.
- `freeCompilerArgs += ["-jvm-default=no-compatibility", "-Xjsr305=strict"]`.
- `withSourcesJar()` + `withJavadocJar()` for IDE support.
- Spotless ktfmt for formatting.
- A `verifyJvmTarget` Gradle task asserts the compiled bytecode is major version 55 (Java 11) and is wired into `check` to catch toolchain drift between the pin and the published bytecode.

## Testing strategy

Three layers:

1. **Unit tests** in `src/test/kotlin/` for both modules. Mocked transport / stub `JobClient`. No network. Examples:
   - `JdkHttpTransportTest` — uses `com.sun.net.httpserver.HttpServer` on an ephemeral port to assert request shapes and decode responses for every route. No external dependency.
   - `ErrorMapperTest` — table-driven `(status, body) → typed exception`.
   - `JobContextImplTest` — replay correctness, name-mismatch warn-and-continue, duplicate-name warn-and-continue, lazy pagination — all without HTTP.
   - `CodecsTest` / `JacksonPayloadCodecTest` — null pass-through for every built-in codec.
   - `CreateJobRequestBuilderTest` — covers all four factory variants and the `Builder<Nothing>.inputData(bytes)` extension.
   - `HandlerRegistrationTest` — pins down the `JobHandler`-as-singleton-factory contract.
   - `RoundRobinAcquireStrategyTest`, `WorkerConfigBuilderTest`, etc.
2. **Java compatibility test** (`JavaCompatTest.java`) — constructs the manager, registers a Java-lambda handler, calls `ctx.run` with the `Class<T>` overload. **Compile-failure of this file = public API regression for Java users.**
3. **Integration tests** in `logplay-client/src/test/kotlin/.../integration/` — tagged `@Tag("integration")`. Excluded from the default `test` task; included in the dedicated `integrationTest` task. They probe the configured server URL at `@BeforeAll` via `ServerReachabilityExtension` and **fail loudly** with an actionable message when the server is down. Tests use **unique IDs per test** (UUID-suffixed `groupId` / `workerId` / `idempotencyKey`) so they're safe against a shared persistent backend.

## Open trade-offs

| Area | Current choice | Rationale | Future revisit if… |
|---|---|---|---|
| Acquire amplification | One HTTP call per registered type per loop iteration | Server's acquire endpoint is single-type | Hot path; could propose a multi-type variant on the server |
| Partial-batch acquire | `< limit` returned ⇒ assume no more pending, back off | Matches typical poll semantics | Server documents a different convention |
| Checkpoint name semantics | Debug-only; replay determinism is position-based; duplicate/mismatched names warn but don't throw | Matches journal-replay norm; lets handlers be renamed safely | Real fan-out journal entries (multiple kinds per position) make name-as-disambiguator more attractive |
| Sequential `ctx.run` only | No fan-out within a single job | v1 simplicity | Real fan-out workloads emerge |
| `ErrorMapper` matches on message text | Server doesn't expose error codes | Practical for now | Server adds machine-readable error codes |
| `compileOnly` coroutines | Zero cost for Java users | Java is the dominant integration | Demand for first-class suspend in core |
| `groupId` per-manager (Kafka-style) | Service typically belongs to one consumer group | Avoids repeating it on every `newWorker(...)` and `createJob(...)` | A single service legitimately serves multiple groups (escape hatch: `manager.createJob(req, override)`) |
| Codec contract is nullable on both sides | Wire fields are nullable; sites become pure pass-through; codec author handles null explicitly | Single source of truth; no per-site `?.let` | If the wire ever becomes mixed-nullability, codec contract may need to bifurcate |
