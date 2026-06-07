package org.zeplinko.logplay.client.handler

import java.time.Duration
import java.util.concurrent.Callable
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.codec.TypeToken

/**
 * Per-job execution context exposed to [JobHandler.execute]. Provides durable execution: each call
 * to `run(name, ...)` persists a checkpoint and, on replay, returns the previously-recorded result
 * instead of re-executing the block.
 *
 * ### Checkpoint name semantics
 *
 * The `name` parameter is **debug metadata**. It appears in logs/traces and helps identify
 * checkpoints by hand. Replay determinism is based on **position**, not name:
 * - Renaming a step across deploys is safe.
 * - Reusing a name within a single execution is safe (replay still works) but logs a warning, since
 *   unique names make logs and traces easier to follow.
 * - Adding, removing, or reordering `run` calls between executions *does* break determinism and is
 *   the user's responsibility to avoid.
 *
 * ### Nullable values
 *
 * `run` is declared with a non-nullable `T` return type. To allow a step to produce `null`, bind
 * `T` to a nullable type at the call site — typically via the reified extension:
 * `ctx.run<Foo?>("name") { … }`. The Java-form `run("name", Foo::class.java) { … }` enforces
 * non-null at compile time; the only way to opt into null is through the reified extension with an
 * explicit nullable type argument.
 *
 * For steps that have no result at all, prefer [runVoid].
 */
public interface JobContext {
    public fun jobId(): String

    public fun workerId(): String

    @Throws(Exception::class)
    public fun <T> run(name: String, resultType: Class<T>, block: Callable<T>): T

    @Throws(Exception::class)
    public fun <T> run(name: String, resultType: TypeToken<T>, block: Callable<T>): T

    @Throws(Exception::class)
    public fun <T> run(name: String, codec: PayloadCodec<T>, block: Callable<T>): T

    @Throws(Exception::class) public fun runVoid(name: String, block: Runnable)

    /**
     * Durably sleep for the given duration. The first time the handler reaches this call, the SDK
     * computes an absolute wake-at deadline (`now + duration`) and persists it as a checkpoint at
     * the current journal position; on every subsequent replay, the recorded deadline is read back
     * and the same wake-at is honoured — the duration argument is only consulted on the first call.
     *
     * Behaviour after the deadline is computed:
     * - If the remaining wait is at most the worker's short-sleep threshold (default 5s), the
     *   handler thread blocks via [Thread.sleep] and returns normally.
     * - Otherwise, the SDK throws `ReleaseJobError` (a subclass of [Error]) — caught by the worker
     *   runtime, which releases the job back to the queue with an `availableAt` hint set to the
     *   deadline. The job will not be re-acquired by any worker until that wall-clock time has
     *   elapsed; on re-acquire, the handler replays from the start, reads the same deadline from
     *   the checkpoint, and proceeds.
     *
     * **Do not catch** [Error] or any `LogPlayNeverCatchError` subclass in your handler — doing so
     * will turn a long sleep into a thread-blocking wait. See `LogPlayNeverCatchError` for the
     * rationale.
     *
     * @param name debug-only name for the sleep checkpoint (same semantics as [run]'s `name`
     *   parameter).
     * @param duration absolute requested sleep length. Must be non-negative; a zero duration is a
     *   no-op the first time and a no-op on replay.
     * @throws Exception if persisting the sleep checkpoint fails, the wait is interrupted, or the
     *   SDK signals a long-sleep release (control-flow signal — see method-level docs).
     */
    @Throws(Exception::class) public fun sleep(name: String, duration: Duration)

    /**
     * Convenience overload — equivalent to `sleep(name, Duration.ofMillis(durationMs))`.
     *
     * @param name debug-only name for the sleep checkpoint.
     * @param durationMs requested sleep length in milliseconds. Must be non-negative.
     * @throws Exception same conditions as [sleep].
     */
    @Throws(Exception::class) public fun sleep(name: String, durationMs: Long)
}
