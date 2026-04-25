package org.zeplinko.logplay.client.exception;

/**
 * Internal control-flow signal thrown at a {@code JobContext.run(...)} / {@code JobContext.sleep(...)}
 * boundary when the owning worker has begun stopping. The acquire loop catches this and releases the
 * job back to {@code PENDING} (available immediately) so another worker can pick it up and resume from
 * the last durable checkpoint.
 *
 * <p>Cooperative cancellation is checkpoint-bounded: a step already executing when the stop was
 * requested runs to completion and is saved; the <em>next</em> {@code ctx.run} / {@code ctx.sleep}
 * is the gate that throws this. No work already checkpointed is lost — replay on the next worker
 * resumes after it.
 *
 * <p>If user code accidentally catches this, the release won't happen and the job will keep running
 * past the requested shutdown until the worker's grace period forcibly interrupts the handler thread
 * — never a correctness bug, but it defeats the fast, cooperative shutdown. See
 * {@link LogPlayNeverCatchError} for why this is an {@link Error} rather than a
 * {@link RuntimeException}.
 */
public final class WorkerStoppingError extends LogPlayNeverCatchError {

    public WorkerStoppingError(String jobId) {
        super("Worker stopping — releasing job " + jobId);
    }
}