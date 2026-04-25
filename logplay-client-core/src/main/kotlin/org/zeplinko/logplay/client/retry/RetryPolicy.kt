package org.zeplinko.logplay.client.retry

/** Decides how long to wait before retrying a failed operation, or whether to give up. */
public interface RetryPolicy {
    /**
     * @param attempt 1-based attempt number that just failed
     * @param lastError the throwable from the last attempt
     * @return delay in milliseconds before the next attempt, or a negative value to stop retrying
     */
    public fun nextDelayMs(attempt: Int, lastError: Throwable): Long
}
