package org.zeplinko.logplay.client.worker

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class HandlerExecutor(workerId: String, poolSize: Int) {
    private val threadCounter = AtomicLong(0)
    private val factory = ThreadFactory { r ->
        Thread(r, "logplay-worker-$workerId-handler-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val pool: ThreadPoolExecutor =
        ThreadPoolExecutor(
            poolSize,
            poolSize,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue<Runnable>(),
            factory,
        )
    private val semaphore = Semaphore(poolSize, false)

    fun freeSlots(): Int = semaphore.availablePermits()

    /** Try to reserve a slot without blocking. Returns true if reserved. */
    fun tryReserve(): Boolean = semaphore.tryAcquire()

    fun release() {
        semaphore.release()
    }

    fun submit(task: Runnable) {
        pool.execute(task)
    }

    fun shutdown(graceMs: Long) {
        pool.shutdown()
        try {
            if (!pool.awaitTermination(graceMs, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow()
            }
        } catch (_: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
