# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The **JVM Client SDK** for the LogPlay durable execution server. The server lives in a separate repository at `/Volumes/Workspace/LogPlay/logplay-server`. This repo is consumed as a library by JVM applications (Kotlin or Java) that want to submit and execute LogPlay jobs.

## Build & development commands

```bash
# Format (always before commit)
./gradlew spotlessApply

# Unit tests + Java-compat test (no server needed)
./gradlew build

# Integration tests against an externally-running server
./gradlew :logplay-client:integrationTest

# Override the integration test server URL
./gradlew :logplay-client:integrationTest -Plogplay.server.baseUrl=http://localhost:9090
LOGPLAY_SERVER_BASE_URL=http://localhost:9090 ./gradlew :logplay-client:integrationTest

# Verify Java 11 bytecode target (wired into `check`)
./gradlew :logplay-client:verifyJvmTarget
```

To run integration tests end-to-end:
1. In one shell, in the server repo: `./gradlew :logplay-server-h2:run`
2. In another shell, in this repo: `./gradlew :logplay-client:integrationTest`

## Architecture

Two Gradle modules under `org.zeplinko.logplay.client.*`:

### `logplay-client-core` — pure module
No HTTP, no Jackson. Only depends on Kotlin stdlib + `slf4j-api`. Future Android-only / native ports re-implement only the JVM module against this core.

- `codec/` — `PayloadCodec<T>` interface (nullable in/out: `encode(T?): ByteArray?`, `decode(ByteArray?): T?`), `TypeToken<T>` (generic capture, Jackson-free), built-in `Codecs` object (`BYTES`, `UTF8`, `UNIT`).
- `exception/` — `LogPlayClientException` root + full hierarchy mirroring server status codes (validation/not-found/forbidden/conflict). `CheckpointDivergenceException` exists but is currently unthrown (reserved for future operation-kind divergence).
- `model/` — Domain types: `Job`, `Checkpoint` (data is `ByteArray?`), `CheckpointPage`, `JobEvent`, `WorkerInfo`, `CreateJobRequest<I>` (generic; built via factory variants — see Java compatibility), `JobStatus` enum.
- `handler/` — **`JobHandler<I, O>`** and **`HandlerFactory<I, O>`** are Kotlin `fun interface`s (Java callers still get SAM-conversion via the bytecode-emitted `@FunctionalInterface` marker). **`JobContext`** is a Kotlin `interface`. All three use non-null `: T` / `(I, …): O` signatures — nullability lives on the type parameters: `JobHandler<Foo, Bar>` for non-null I/O, `JobHandler<Foo?, Bar?>` if the handler legitimately accepts null input or returns null output (see `JsonPayloadCodecIT` for an example). `JobContext.run` returns non-null `T`; the reified `ctx.run<T>(...)` / `ctx.run<T?>(...)` extension in `SuspendExtensions.kt` carries the nullability through `T::class.java` plus reified-aware Kotlin checks. `JobContextImpl.run` performs the underlying codec call and uses an `@Suppress("UNCHECKED_CAST") as T` to bridge `codec.decode`'s `T?` return into the interface's `T` (the cast is JVM-erased; non-null is enforced at the caller's reified boundary). `HandlerRegistration` carries `(type, inputCodec, outputCodec, factory)` — the `JobHandler`-form constructor wraps it in a singleton-returning factory for zero per-invocation allocation.
- `retry/` — `RetryPolicy` interface, `ExponentialBackoffRetryPolicy` impl, `Retryable` marker.

### `logplay-client` — JVM implementation
Depends on `logplay-client-core` (api). Adds Jackson + JDK HTTP + worker runtime + durable runtime.

- `LogPlayManager.kt` — public entry point with nested `Builder` (`@JvmStatic builder()`). Builder requires both `baseUrl` and `groupId`. Exposes `createJob(req)` / `createJob(req, groupIdOverride)`, `newWorker(workerId, config?)`, and `jsonCodec(Class<T>)` / `jsonCodec(TypeToken<T>)` codec primitives.
- `codec/JacksonPayloadCodec` — default codec implementation; null in → null out.
- `http/` — internal: `HttpTransport` interface, `JdkHttpTransport` (uses JDK 11 `java.net.http.HttpClient`), `HttpRoutes`, `ErrorMapper` (maps non-2xx responses to typed exceptions via status code + message keyword matching).
- `dto/` — internal Jackson-annotated wire DTOs (`JobDtos`, `WorkerDtos`) with `toDomain()` / `toDto(...)` extensions. The encoding decision (typed source vs raw bytes vs codec) lives in `JobDtos.toDto(groupId, mapper)` and dispatches on `CreateJobRequest.source: InputSource<I>`. Domain types in `core.model.*` stay Jackson-free.
- `job/JobClient` — internal facade, one method per server endpoint. Carries the manager's `ObjectMapper` so `createJob<I>(groupId, request)` can resolve typed encoding at submit time.
- `worker/` — `Worker` (public), `WorkerConfig` (public), and internals: `HandlerRegistry`, `HandlerExecutor` (bounded `ThreadPoolExecutor` + `Semaphore` for accurate free-slot tracking), `AcquireLoop` (single dedicated thread; calls `factory.create()` per job), `HeartbeatLoop` (`ScheduledExecutorService`), `AcquireStrategy` interface + `RoundRobinAcquireStrategy` default. `Worker.registerHandler` has 6 overloads: 3 codec/Class/TypeToken × 2 single-instance/factory.
- `runtime/` — `JobContextImpl` (implements `core.handler.JobContext`; null callable result → null wire data → null on replay), `CheckpointReplayState` (lazy-paginated cursor), `CheckpointSaver` (HTTP saver with chain-ahead recovery on 409).
- `retry/RetryExecutor` — wraps a `Callable` with `RetryPolicy`-driven retries; only retries on exceptions implementing `Retryable`.
- `SuspendExtensions.kt` — Kotlin `suspend` wrappers (`createJobAwait`, `abortJobAwait`, `getCheckpointsAwait`, `getEventsAwait`) **and** reified extensions (`Worker.registerHandler<I, O>`, `Worker.registerHandlerFactory<I, O>`, `LogPlayManager.jsonCodec<T>`, top-level `createJobRequestBuilder<I>()`). Compiled with `compileOnly` coroutines so they cost nothing for Java users; reified extensions are top-level `inline fun` and are invisible to Java.

## Java compatibility — non-negotiable

This SDK MUST be ergonomic from Java. Code rules:

- **Public API uses no Kotlin-only types**: no default arguments visible to Java (use `@JvmOverloads` or explicit overloads), no `Result`, no `Pair`, no top-level `suspend` in public API.
- **`JobHandler` and `HandlerFactory` are Kotlin `fun interface`s; `JobContext` is a Kotlin `interface`.** All three live in `logplay-client-core/src/main/kotlin/.../handler/`. Java callers get SAM-conversion for the two `fun interface`s via the bytecode-emitted `@FunctionalInterface` marker. Nullability lives on the type parameters — see the `handler/` bullet above for details. The `Codecs.UNIT.decode(null)` collapse to `Unit` (asymmetric on purpose) is what lets `JobHandler<Unit, *>` handlers receive jobs created without `inputData` without an NPE at method entry.
- **Builders for required-field DTOs**: `WorkerConfig`, `LogPlayManager.Builder` are hand-written builders. `CreateJobRequest<I>` is generic and uses **factory variants** instead of one builder: `builder(Class<I>)`, `builder(TypeToken<I>)`, `builder(PayloadCodec<I>)`, and `builder()` (returns `Builder<Nothing>` for no-input requests). `Builder<Nothing>.inputData(bytes)` is a top-level extension scoping the raw-bytes escape hatch to no-input builders only.
- **`@JvmStatic`** on companion factories (`LogPlayManager.builder()`, `CreateJobRequest.builder(...)`, `JacksonPayloadCodec.of(...)`, `WorkerConfig.defaults()`).
- **`@JvmField`** on public constants (e.g., `Codecs.BYTES`).
- **`@JvmOverloads`** on Kotlin methods/constructors with default values that are visible publicly.
- **Reified Kotlin extensions** (`Worker.registerHandler<I, O>`, `Worker.registerHandlerFactory<I, O>`, `LogPlayManager.jsonCodec<T>`) live in `SuspendExtensions.kt` next to the Class/TypeToken-form Java overloads. `registerHandlerFactory` uses a distinct name from `registerHandler` to avoid SAM-conversion ambiguity at lambda call sites; the Java side has both as overloads of `registerHandler`.
- A `JavaCompatTest.java` lives in `logplay-client/src/test/java/...`. **If that file stops compiling, the public API broke for Java users.** Don't break it.

## Build conventions

- Kotlin 2.3, target JVM 11 (`jvmToolchain(11)`, `JvmTarget.JVM_11`) — pinned in `buildSrc/src/main/kotlin/kotlin-client-module-base.gradle.kts`. This is the bytecode that ships to consumers.
- `buildSrc` itself has no toolchain pin — Gradle 9.4+ handles JDK 25 hosts cleanly without forwarding an unsupported target to the embedded Kotlin compiler. The Gradle daemon JDK is whatever the developer launches Gradle with; Gradle 9.x supports running on JDK 17–26. Toolchains for compilation are auto-provisioned via Foojay if missing.
- `explicitApiWarning()` — explicit visibility on every public symbol; aim to tighten to strict before v1.
- `freeCompilerArgs += ["-jvm-default=no-compatibility", "-Xjsr305=strict"]`.
- `withSourcesJar()` + `withJavadocJar()` for downstream IDE support.
- Conventions live in `buildSrc/src/main/kotlin/`:
  - `module-base.gradle.kts` — Spotless ktfmt + group/version.
  - `kotlin-client-module-base.gradle.kts` — Kotlin + Java 11 + explicitApiWarning.
- Dependencies are referenced via the version catalog at `gradle/libs.versions.toml`.
- Spotless ktfmt enforces `kotlinlangStyle()` formatting — run `./gradlew spotlessApply` before committing.
- A `verifyJvmTarget` Gradle task asserts the compiled bytecode is major version 55 (Java 11). Wired into `check`. This is the safety net that catches any drift between the toolchain pin and the actual published bytecode.

## Testing

- **Unit tests** in `src/test/kotlin/` for both modules. JUnit Jupiter 5 + AssertJ + Mockito.
- **`JavaCompatTest.java`** in `logplay-client/src/test/java/...` — see above.
- **Integration tests** in `logplay-client/src/test/kotlin/.../integration/` — tagged `@Tag("integration")`. Default `test` task **excludes** that tag; the dedicated `integrationTest` task **includes** it. Same source set as unit tests, just tag-gated. Pattern matches the LogPlay server repo.
- Integration tests probe the configured server URL at `@BeforeAll` via `ServerReachabilityExtension` and **fail loudly** with an actionable message if the server is down.
- `groupId` is **per-class** (`AbstractIntegrationTest.setUpManager` builds the manager with one UUID-suffixed `groupId` for the whole test class). `workerId`, `type`, and `idempotencyKey` are still **per-test** so tests within a class can't collide on a shared persistent backend.

## Server contract (what we consume)

The server's HTTP API is mounted under `/api/v1`. All bodies are JSON; binary fields (`inputData`, `outputData`, checkpoint `data`) are **nullable** Base64-encoded strings on the wire — null and empty bytes are distinct, both are valid. Errors come back as `{"error": "..."}` with status codes 400/403/404/409/5xx, mapped by `ErrorMapper` to the typed exception hierarchy in `core.exception`.

When the server's error wording changes, update `ErrorMapper`'s message-keyword patterns and add a regression test in `ErrorMapperTest`.

## Common gotchas

- The server has **no `GET /jobs/:id`** endpoint. To assert handler results in tests, use a `CompletableFuture` set inside the handler body — don't try to "re-fetch" the finished job from outside.
- Checkpoint names are **debug metadata** — replay determinism is position-based, not name-based. Reusing a name within an execution and renaming a step across executions are both safe; both produce a warn log. There is **no** `DuplicateCheckpointNameException` — if you see references to it in old branches, it was deleted.
- Adding/removing/reordering `ctx.run` calls between executions DOES break determinism (handler structure must be stable across replays). The codec's decode failure is the natural error signal when a step's type changes at the same position.
- `ctx.run` is **strictly sequential**. Parallel branches inside one job are out of scope for v1.
- `groupId` is committed at `LogPlayManager.Builder.groupId(...)` — it's a service-wide identity (Kafka-consumer style). For the rare cross-group submit, use `manager.createJob(req, groupIdOverride)`.
- The JDK `HttpClient` has no `close()` until JDK 21. We don't pretend to close it, and the manager holds no other closeable resources (no callback pool) — so `LogPlayManager` has no `close()` method.
- `kotlinx-coroutines-core` is `compileOnly`. Java users get no transitive coroutine cost; Kotlin users wanting suspend extensions must add `kotlinx-coroutines-jdk8` to their own classpath.

## When making changes

1. Format: `./gradlew spotlessApply`.
2. Build + unit + Java-compat: `./gradlew build`.
3. If you touched anything that talks to the server (`ErrorMapper`, DTOs, `JobClient`, `Worker`, `JobContextImpl`), run integration tests against a live server: `./gradlew :logplay-client:integrationTest`.
4. If you change the public API surface, the `JavaCompatTest.java` should still compile. If you intentionally broke it, update the test.
