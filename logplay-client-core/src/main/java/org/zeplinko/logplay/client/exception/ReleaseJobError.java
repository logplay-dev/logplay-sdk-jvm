package org.zeplinko.logplay.client.exception;

/**
 * Internal control-flow signal thrown by {@code JobContext.sleep(...)} when the remaining wait
 * exceeds the worker's short-sleep threshold. The acquire loop catches this and releases the job
 * back to the queue with the carried {@link #wakeAtMs} as the server's {@code availableAt} hint —
 * the job won't be picked up again until that wall-clock time has elapsed.
 *
 * <p>The wake-at deadline is the source of truth in the checkpoint chain (see
 * {@code JobContextImpl.sleep}); this exception is purely the in-process plumbing that carries it
 * from the handler to the runtime. If user code accidentally catches this, the release won't
 * happen and the job will block a worker thread until the original {@code Thread.sleep} would
 * have completed — never a correctness bug, but a serious efficiency one. See
 * {@link LogPlayNeverCatchError} for the rationale on why this is an {@link Error} rather than a
 * {@link RuntimeException}.
 */
public final class ReleaseJobError extends LogPlayNeverCatchError {
    private static final long serialVersionUID = 1L;

    private final long wakeAtMs;

    public ReleaseJobError(long wakeAtMs) {
        super("Release job until " + wakeAtMs);
        this.wakeAtMs = wakeAtMs;
    }

    /**
     * @return absolute epoch-millis at which the job becomes eligible for re-acquisition.
     */
    public long wakeAtMs() {
        return wakeAtMs;
    }
}
