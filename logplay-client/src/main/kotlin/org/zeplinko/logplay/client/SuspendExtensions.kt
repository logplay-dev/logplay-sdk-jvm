@file:JvmName("SuspendExtensions__Internal")

package org.zeplinko.logplay.client

import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.handler.HandlerFactory
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CheckpointPage
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.Job
import org.zeplinko.logplay.client.model.JobEvent
import org.zeplinko.logplay.client.worker.Worker

// Suspend mirrors of the blocking LogPlayManager methods. Each offloads the blocking call via
// `withContext` to an injectable [CoroutineDispatcher] that defaults to `Dispatchers.IO` (override
// it in tests). The coroutines dependency is `compileOnly` — Java users incur zero coroutine cost;
// Kotlin users wanting these helpers must add `kotlinx-coroutines-core` to their own classpath.

/** Suspend mirror of [LogPlayManager.createJob] — runs the blocking call on [dispatcher]. */
public suspend fun <I> LogPlayManager.createJobAwait(
    request: CreateJobRequest<I>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Job = withContext(dispatcher) { createJob(request) }

/** Suspend mirror of [LogPlayManager.abortJob] — runs the blocking call on [dispatcher]. */
public suspend fun LogPlayManager.abortJobAwait(
    jobId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Job = withContext(dispatcher) { abortJob(jobId) }

/** Suspend mirror of [LogPlayManager.getCheckpoints] — runs the blocking call on [dispatcher]. */
public suspend fun LogPlayManager.getCheckpointsAwait(
    jobId: String,
    after: String? = null,
    limit: Int? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): CheckpointPage = withContext(dispatcher) { getCheckpoints(jobId, after, limit) }

/** Suspend mirror of [LogPlayManager.getEvents] — runs the blocking call on [dispatcher]. */
public suspend fun LogPlayManager.getEventsAwait(
    jobId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): List<JobEvent> = withContext(dispatcher) { getEvents(jobId) }

// Reified ergonomics ---------------------------------------------------------------------------
// Top-level inline extensions visible only to Kotlin callers — Java users see the equivalent
// Class<T>/TypeToken<T>-form overloads on the same types.

/**
 * Build a [CreateJobRequest] whose input is JSON-encoded for the reified type [I]. Equivalent to
 * `CreateJobRequest.builder(I::class.java)`.
 */
public inline fun <reified I> createJobRequestBuilder(): CreateJobRequest.Builder<I> =
    CreateJobRequest.builder(I::class.java)

/**
 * Register a JSON-encoded handler (single shared instance) with reified [I] and [O]. Equivalent to
 * the `(type, Class<I>, Class<O>, JobHandler<I, O>)` overload — Kotlin handles the type tokens via
 * reified generics.
 */
public inline fun <reified I, reified O> Worker.registerHandler(
    type: String,
    handler: JobHandler<I, O>,
): Worker = registerHandler(type, I::class.java, O::class.java, handler)

/**
 * Register a JSON-encoded handler factory with reified [I] and [O]. The Kotlin name differs from
 * [registerHandler] to avoid ambiguity with the [JobHandler]-form extension at SAM lambda call
 * sites — both interfaces are SAMs and the lambda body shape would be ambiguous to the compiler.
 * From Java, both are simply overloads of `registerHandler`.
 */
public inline fun <reified I, reified O> Worker.registerHandlerFactory(
    type: String,
    factory: HandlerFactory<I, O>,
): Worker = registerHandler(type, I::class.java, O::class.java, factory)

/**
 * Construct a JSON [PayloadCodec] for the reified type [T] using this manager's mapper. Useful for
 * mix-and-match cases on [Worker.registerHandler] (e.g., JSON input + custom output codec).
 */
public inline fun <reified T> LogPlayManager.jsonCodec(): PayloadCodec<T> = jsonCodec(T::class.java)

/**
 * Reified, JSON-encoded durable step. The reified type parameter [T] carries nullability — write
 * `ctx.run<Abc>("…") { … }` when the result is non-nullable, or `ctx.run<Abc?>("…") { … }` when it
 * may be null. The Kotlin interface declares `: T` non-null, so when [T] is bound to a non-nullable
 * type Kotlin enforces non-null at the call site; when [T] is bound to a nullable type the codec
 * contract allows the null through.
 */
public inline fun <reified T> JobContext.run(name: String, noinline block: () -> T): T =
    run(name, T::class.java, Callable(block))
