package org.zeplinko.logplay.client.model

/**
 * Server-tracked job state.
 * - **PENDING** — created, not yet acquired by any worker.
 * - **ACQUIRED** — checked out by a worker; running or about to run.
 * - **FINISHED** — handler completed successfully (terminal).
 * - **FAILED** — exhausted retries (terminal).
 * - **ABORTED** — explicitly aborted via `LogPlayManager.abortJob` (terminal).
 */
public enum class JobStatus {
    PENDING,
    ACQUIRED,
    FINISHED,
    FAILED,
    ABORTED,
}
