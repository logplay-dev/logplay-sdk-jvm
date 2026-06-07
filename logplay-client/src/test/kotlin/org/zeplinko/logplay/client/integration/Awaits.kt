package org.zeplinko.logplay.client.integration

import java.time.Duration

internal object Awaits {

    fun <T : Any> awaitNotNull(
        timeout: Duration = Duration.ofSeconds(15),
        pollMs: Long = 100,
        description: String = "non-null value",
        supplier: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val v = supplier()
            if (v != null) return v
            sleepOrThrow(pollMs, description)
        }
        throw AssertionError("Did not get $description within $timeout")
    }

    fun awaitTrue(
        timeout: Duration = Duration.ofSeconds(15),
        pollMs: Long = 100,
        description: String = "condition",
        condition: () -> Boolean,
    ) {
        awaitNotNull(timeout, pollMs, description) { if (condition()) Unit else null }
    }

    private fun sleepOrThrow(ms: Long, description: String) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AssertionError("Interrupted while awaiting $description", e)
        }
    }
}
