package org.zeplinko.logplay.client.retry

import java.util.concurrent.ThreadLocalRandom

/**
 * Exponential backoff with full jitter: `delay = random(0, min(maxDelay, base * 2^(attempt-1)))`.
 * Stops retrying after [maxAttempts] failures.
 */
public class ExponentialBackoffRetryPolicy
@JvmOverloads
constructor(
    public val maxAttempts: Int = 5,
    public val baseDelayMs: Long = 100,
    public val maxDelayMs: Long = 5_000,
    public val jitter: Boolean = true,
) : RetryPolicy {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(baseDelayMs >= 0) { "baseDelayMs must be >= 0" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
    }

    override fun nextDelayMs(attempt: Int, lastError: Throwable): Long {
        if (attempt >= maxAttempts) return -1
        val shift = (attempt - 1).coerceIn(0, 30)
        val raw = baseDelayMs shl shift
        val capped = if (raw < 0 || raw > maxDelayMs) maxDelayMs else raw
        return if (jitter && capped > 0) ThreadLocalRandom.current().nextLong(0, capped + 1)
        else capped
    }
}
