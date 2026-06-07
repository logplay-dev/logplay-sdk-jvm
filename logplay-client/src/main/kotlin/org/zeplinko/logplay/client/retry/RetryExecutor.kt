package org.zeplinko.logplay.client.retry

import java.util.concurrent.Callable
import org.slf4j.LoggerFactory

internal class RetryExecutor(private val policy: RetryPolicy) {

    fun <T> run(opName: String, op: Callable<T>): T {
        var attempt = 1
        while (true) {
            try {
                return op.call()
            } catch (e: Throwable) {
                val retryable = e is Retryable
                if (!retryable) throw e
                val delay = policy.nextDelayMs(attempt, e)
                if (delay < 0) {
                    log.error("Giving up on '{}' after {} attempts", opName, attempt, e)
                    throw e
                }
                log.warn(
                    "Retrying '{}' (attempt {}, delay {}ms): {}",
                    opName,
                    attempt,
                    delay,
                    e.toString(),
                )
                if (delay > 0) {
                    try {
                        Thread.sleep(delay)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
                attempt++
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RetryExecutor::class.java)
    }
}
