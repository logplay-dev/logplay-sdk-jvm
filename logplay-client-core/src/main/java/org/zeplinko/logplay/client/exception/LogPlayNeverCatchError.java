package org.zeplinko.logplay.client.exception;

/**
 * Base class for SDK control-flow signals that user code <b>must not catch</b>. Extends
 * {@link Error} (not {@link RuntimeException}) so a typical {@code catch (Exception e)} or
 * {@code catch (Throwable t)} in handler code will not silently swallow it — well-behaved user
 * code catches {@link Exception}, not {@link Error}.
 *
 * <p>The SDK throws subclasses of this to signal cooperative state transitions to the worker
 * runtime (for example, "release this job until time T" via {@link ReleaseJobError}). The
 * acquire loop catches these explicitly and translates them into the appropriate server call.
 *
 * <p><b>Do not catch this or any subclass in handler code.</b> If you have a try/catch around an
 * entire handler body, exclude {@link Error} from it (catch {@link Exception} only) — otherwise
 * you will defeat the SDK's durable execution machinery.
 */
public abstract class LogPlayNeverCatchError extends Error {
    private static final long serialVersionUID = 1L;

    protected LogPlayNeverCatchError(String message) {
        super(message);
    }
}
