package org.zeplinko.logplay.client.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.zeplinko.logplay.client.codec.JacksonPayloadCodec
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.codec.TypeToken
import org.zeplinko.logplay.client.handler.HandlerFactory
import org.zeplinko.logplay.client.handler.HandlerRegistration
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.job.JobClient
import org.zeplinko.logplay.client.retry.RetryExecutor
import org.zeplinko.logplay.client.retry.RetryPolicy
import org.zeplinko.logplay.client.runtime.CheckpointSaver

/**
 * A LogPlay worker — polls the server for jobs of registered types and dispatches them to your
 * handlers.
 *
 * A worker inherits its `groupId` from the [LogPlayManager] that created it. One worker can handle
 * many job types within that group; for multiple groups, build separate managers.
 *
 * Build via `manager.newWorker(workerId, config?)`. The returned worker is **not** started — wire
 * up handlers via [registerHandler] (chainable, returns the worker), then call [start] to begin
 * polling. In a standalone process, follow [start] with [awaitTermination] to park `main` until the
 * worker stops (all worker threads are daemons, so otherwise the JVM exits immediately); optionally
 * call [installShutdownHook] for a clean stop on `Ctrl+C` / `SIGTERM`.
 *
 * ### Threading
 * - **Acquire loop** — one dedicated daemon thread.
 * - **Heartbeat loop** — single-threaded `ScheduledExecutorService`.
 * - **Handler executor** — bounded `ThreadPoolExecutor` sized by [WorkerConfig.executorPoolSize].
 *
 * Lifecycle operations ([start] / [stop]) are thread-safe — they may be called concurrently from
 * different threads (serialized internally via a lifecycle lock). Per-job execution is thread-safe
 * too; handlers run concurrently up to the pool size.
 *
 * ### Lifecycle
 *
 * Each worker is single-use:
 * - `NEW → STARTING → RUNNING → STOPPING → STOPPED`.
 * - [start] registers with the server, spins up the loops, transitions to `RUNNING`.
 * - [stop] signals in-flight jobs to bail at their next checkpoint boundary (releasing them back to
 *   PENDING), waits up to [WorkerConfig.shutdownGraceMs] for them to do so, then `DELETE`s the
 *   worker server-side so any still-acquired jobs return to PENDING.
 * - The heartbeat loop self-stops the worker if the server condemns or forgets it.
 */
public class Worker
internal constructor(
    private val groupId: String,
    private val workerId: String,
    private val config: WorkerConfig,
    private val client: JobClient,
    private val mapper: ObjectMapper,
    retryPolicy: RetryPolicy,
) {
    private val registry = HandlerRegistry()
    private val executor = HandlerExecutor(workerId, config.executorPoolSize)
    private val retry = RetryExecutor(retryPolicy)
    private val state = AtomicReference(State.NEW)
    // Serializes start() against stopAsync()'s state transition, so a concurrent stop can never
    // interleave with start()'s multi-step setup (no zombie/leaked threads, no stop() hang).
    private val lifecycleLock = Any()
    private val acquireLoop = AtomicReference<AcquireLoop?>(null)
    private val heartbeat = AtomicReference<HeartbeatLoop?>(null)
    private val terminationLatch = CountDownLatch(1)
    private val shutdownHook = AtomicReference<Thread?>(null)

    /** The `groupId` this worker is bound to (inherited from the parent [LogPlayManager]). */
    public fun groupId(): String = groupId

    /** The worker's server-side identity within [groupId]. */
    public fun workerId(): String = workerId

    /** True after [start] returns successfully and before [stop] is called. */
    public fun isRunning(): Boolean = state.get() == State.RUNNING

    /**
     * True once shutdown has begun (state is `STOPPING` or `STOPPED`). This is the single stop
     * signal read by the acquire loop's `while (!stopRequested())` and by `JobContext` to bail
     * in-flight jobs at their next checkpoint boundary.
     */
    private fun isStopping(): Boolean =
        state.get().let { it == State.STOPPING || it == State.STOPPED }

    // --- Single-instance handler registrations -----------------------------------------------
    // The same JobHandler instance is reused for every job of [type]. Implementations must be
    // thread-safe. Prefer this for stateless handlers — zero per-invocation allocation.

    /**
     * Register a handler for [type] using the supplied input/output codecs.
     *
     * The same [handler] instance is invoked for every matching job, possibly concurrently across
     * the handler thread pool — implementations must be thread-safe. For per-invocation instances
     * (e.g., DI-scoped beans or per-job mutable state), use the [HandlerFactory] overload.
     *
     * @return this worker for chaining
     */
    public fun <I, O> registerHandler(
        type: String,
        inputCodec: PayloadCodec<I>,
        outputCodec: PayloadCodec<O>,
        handler: JobHandler<I, O>,
    ): Worker = apply {
        registry.register(HandlerRegistration(type, inputCodec, outputCodec, handler))
    }

    /**
     * Class-based variant of [registerHandler]. Builds JSON codecs internally from the manager's
     * [ObjectMapper]. Equivalent to passing `manager.jsonCodec(inputType)` /
     * `manager.jsonCodec(outputType)` to the codec-form overload.
     */
    public fun <I, O> registerHandler(
        type: String,
        inputType: Class<I>,
        outputType: Class<O>,
        handler: JobHandler<I, O>,
    ): Worker =
        registerHandler(
            type,
            JacksonPayloadCodec.of(mapper, inputType),
            JacksonPayloadCodec.of(mapper, outputType),
            handler,
        )

    /** As the [Class]-based overload, but for parameterized generic types via [TypeToken]. */
    public fun <I, O> registerHandler(
        type: String,
        inputType: TypeToken<I>,
        outputType: TypeToken<O>,
        handler: JobHandler<I, O>,
    ): Worker =
        registerHandler(
            type,
            JacksonPayloadCodec.of(mapper, inputType),
            JacksonPayloadCodec.of(mapper, outputType),
            handler,
        )

    // --- Per-invocation factory registrations ------------------------------------------------
    // [factory.create()] is invoked once per job execution. Use this for DI-scoped or stateful
    // handlers (e.g., () -> applicationContext.getBean(MyHandler.class)).

    /**
     * Register a [HandlerFactory] for [type]. The framework calls [factory].create() **once per job
     * execution**, then discards the returned handler when [JobHandler.execute] returns.
     *
     * Use this when handlers should not be shared across jobs — e.g., DI-managed prototype-scoped
     * beans, or handlers that carry per-job mutable state in fields.
     */
    public fun <I, O> registerHandler(
        type: String,
        inputCodec: PayloadCodec<I>,
        outputCodec: PayloadCodec<O>,
        factory: HandlerFactory<I, O>,
    ): Worker = apply {
        registry.register(HandlerRegistration(type, inputCodec, outputCodec, factory))
    }

    /**
     * Class-based variant of the [HandlerFactory] overload. JSON codecs from the manager's mapper.
     */
    public fun <I, O> registerHandler(
        type: String,
        inputType: Class<I>,
        outputType: Class<O>,
        factory: HandlerFactory<I, O>,
    ): Worker =
        registerHandler(
            type,
            JacksonPayloadCodec.of(mapper, inputType),
            JacksonPayloadCodec.of(mapper, outputType),
            factory,
        )

    /** As the Class-based [HandlerFactory] overload, but for parameterized generic types. */
    public fun <I, O> registerHandler(
        type: String,
        inputType: TypeToken<I>,
        outputType: TypeToken<O>,
        factory: HandlerFactory<I, O>,
    ): Worker =
        registerHandler(
            type,
            JacksonPayloadCodec.of(mapper, inputType),
            JacksonPayloadCodec.of(mapper, outputType),
            factory,
        )

    /**
     * Register a pre-built [HandlerRegistration] (advanced — typical use cases prefer the typed
     * overloads above).
     */
    public fun registerHandler(registration: HandlerRegistration<*, *>): Worker = apply {
        registry.register(registration)
    }

    /**
     * Register this worker with the server, then start the acquire and heartbeat loops. Safe to
     * call exactly once per worker instance — re-calling after [stop] is a programming error.
     */
    public fun start() {
        synchronized(lifecycleLock) {
            if (!state.compareAndSet(State.NEW, State.STARTING)) {
                check(state.get() == State.RUNNING) {
                    "Worker $workerId already started or stopped (state=${state.get()})"
                }
                return
            }
            try {
                client.registerWorker(workerId, config.heartbeatTimeoutMs, config.sessionTimeoutMs)
            } catch (e: Throwable) {
                state.set(State.STOPPED)
                terminationLatch.countDown()
                throw e
            }
            val saver = CheckpointSaver(client)
            val loop =
                AcquireLoop(
                    groupId = groupId,
                    workerId = workerId,
                    config = config,
                    registry = registry,
                    executor = executor,
                    client = client,
                    saver = saver,
                    mapper = mapper,
                    retry = retry,
                    onWorkerLost = { stopAsync() },
                    stopRequested = ::isStopping,
                )
            acquireLoop.set(loop)
            loop.start()
            val hb =
                HeartbeatLoop(
                    workerId = workerId,
                    intervalMs = config.heartbeatIntervalMs,
                    client = client,
                    onWorkerLost = { stopAsync() },
                )
            heartbeat.set(hb)
            hb.start()
            state.set(State.RUNNING)
            log.info("Worker {} (group={}) started", workerId, groupId)
        }
    }

    /**
     * Stop accepting new work and shut down the worker, blocking until it reaches `STOPPED`.
     *
     * In-flight jobs are cancelled **cooperatively**: a stop signal is raised and each running
     * handler bails at its next `ctx.run` / `ctx.sleep` boundary, releasing its job back to PENDING
     * so another worker can resume it from the last durable checkpoint. A checkpoint step already
     * executing runs to completion and is saved first — no checkpointed work is lost.
     *
     * Waits for in-flight handlers to reach a checkpoint boundary and bail (up to
     * [WorkerConfig.shutdownGraceMs]); a handler stuck in long non-checkpointed user code past the
     * grace period is then interrupted via `shutdownNow()`. The worker is finally `DELETE`d
     * server-side, releasing any still-acquired jobs back to PENDING for another worker to pick up.
     *
     * The teardown runs on a dedicated thread (shared with the self-stop path); this call blocks
     * **uninterruptibly** until that completes, restoring the thread's interrupt status before
     * returning. Idempotent and safe to call concurrently — every caller returns only once the
     * worker is fully stopped.
     */
    public fun stop() {
        stopAsync()
        // Block uninterruptibly until runShutdown() finishes; remember and restore an interrupt.
        var interrupted = false
        while (true) {
            try {
                terminationLatch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Kick off shutdown without blocking the caller — the single entry point for both [stop] (which
     * adds a blocking wait) and the self-stop path fired by the acquire/heartbeat loops. Safe to
     * call from those loop threads: the teardown runs on a separate thread, so it never self-joins
     * the acquire thread or self-shuts-down the heartbeat scheduler.
     */
    private fun stopAsync() {
        synchronized(lifecycleLock) {
            // Holding lifecycleLock makes this atomic w.r.t. start()'s setup. The CAS to STOPPING
            // is
            // the stop signal (read via isStopping()); the STOPPING/STOPPED guard lets exactly one
            // initiator spawn the teardown thread. Only the *spawn* is under the lock — runShutdown
            // itself runs lock-free, so it never blocks start()/stop().
            val current = state.get()
            if (current == State.STOPPED || current == State.STOPPING) return
            if (!state.compareAndSet(current, State.STOPPING)) return
            Thread({ runShutdown() }, "logplay-worker-$workerId-stop")
                .apply { isDaemon = true }
                .start()
        }
    }

    /** The actual teardown. Always runs on the dedicated stop thread, never the caller's. */
    private fun runShutdown() {
        try {
            // Wait for the acquire loop (the producer) to exit before shutting down the executor
            // (the consumer), so no late submit races the pool shutdown.
            acquireLoop.get()?.awaitExit(config.shutdownGraceMs)
            executor.shutdown(config.shutdownGraceMs)
            heartbeat.get()?.stop()
            try {
                client.deregisterWorker(workerId)
            } catch (e: Throwable) {
                log.warn("Worker {} deregister failed", workerId, e)
            }
        } catch (e: Throwable) {
            // An unexpected failure in a teardown step (e.g. executor/heartbeat shutdown) — log it
            // rather than let it surface as an uncaught exception on the stop thread.
            log.error("Worker {} shutdown step failed", workerId, e)
        } finally {
            // Always mark stopped and release waiters, even if a teardown step threw, so no
            // stop() / awaitTermination() caller can block forever (stop() is uninterruptible).
            state.set(State.STOPPED)
            terminationLatch.countDown()
            log.info("Worker {} stopped", workerId)
        }
    }

    /**
     * Block the calling thread until this worker reaches the `STOPPED` state.
     *
     * Use this to keep a standalone `main()` alive — all of the worker's threads are daemon
     * threads, so without something to park on, the JVM exits the instant `main` returns and tears
     * the worker down. After [start], call this to hand the main thread over to the worker until it
     * stops — either because you (or a [shutdown hook][installShutdownHook]) called [stop], or
     * because the heartbeat loop self-stopped after the server condemned the worker.
     *
     * Returns immediately if the worker is already stopped. Calling this on a worker that was never
     * started parks forever (until some other thread calls [stop]).
     *
     * **Interruptible by design.** Unlike [stop] — a command that drives shutdown and so blocks
     * uninterruptibly — this is a passive wait that changes nothing, so it honors interruption like
     * `Thread.join` / `CountDownLatch.await`: an interrupt unparks the caller with an
     * [InterruptedException] so it can cancel a wait that may otherwise block indefinitely.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @Throws(InterruptedException::class)
    public fun awaitTermination() {
        terminationLatch.await()
    }

    /**
     * Bounded variant of [awaitTermination]: wait up to [timeoutMs] milliseconds for the worker to
     * stop.
     *
     * @return `true` if the worker stopped within the timeout, `false` if the wait elapsed first
     * @throws InterruptedException if the waiting thread is interrupted
     */
    @Throws(InterruptedException::class)
    public fun awaitTermination(timeoutMs: Long): Boolean =
        terminationLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Register a JVM shutdown hook that [stop]s this worker on `Ctrl+C` / `SIGTERM`.
     *
     * Without this, an interrupt kills the JVM (and its daemon threads) abruptly: [stop] never
     * runs, so the worker is not deregistered server-side and any acquired-but-incomplete jobs are
     * only reclaimed once the server expires the session on heartbeat timeout. With the hook
     * installed, shutdown runs [stop] — draining in-flight handlers and `DELETE`ing the worker so
     * its acquired jobs return to `PENDING` immediately.
     *
     * Idempotent: only the first call registers a hook; later calls are no-ops. Safe to call before
     * or after [start].
     *
     * @return this worker for chaining
     */
    public fun installShutdownHook(): Worker = apply {
        val hook = Thread({ stop() }, "logplay-worker-$workerId-shutdown")
        if (shutdownHook.compareAndSet(null, hook)) {
            Runtime.getRuntime().addShutdownHook(hook)
            log.debug("Worker {} registered JVM shutdown hook", workerId)
        }
    }

    private enum class State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
    }

    private companion object {
        private val log = LoggerFactory.getLogger(Worker::class.java)
    }
}
