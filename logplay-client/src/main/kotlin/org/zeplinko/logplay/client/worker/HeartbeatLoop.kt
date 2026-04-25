package org.zeplinko.logplay.client.worker

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import org.zeplinko.logplay.client.exception.WorkerCondemnedException
import org.zeplinko.logplay.client.exception.WorkerNotFoundException
import org.zeplinko.logplay.client.job.JobClient

internal class HeartbeatLoop(
    private val workerId: String,
    private val intervalMs: Long,
    private val client: JobClient,
    private val onWorkerLost: () -> Unit,
) {
    private val counter = AtomicLong(0)
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "logplay-worker-$workerId-heartbeat-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    fun start() {
        scheduler.scheduleAtFixedRate(::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        scheduler.shutdownNow()
    }

    private fun tick() {
        try {
            client.heartbeat(workerId)
        } catch (e: WorkerCondemnedException) {
            log.error("Worker {} condemned by server; will stop", workerId, e)
            onWorkerLost()
        } catch (e: WorkerNotFoundException) {
            log.error("Worker {} not found by server; will stop", workerId, e)
            onWorkerLost()
        } catch (e: Throwable) {
            log.warn("Heartbeat failed for {}; will retry next tick: {}", workerId, e.toString())
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HeartbeatLoop::class.java)
    }
}
