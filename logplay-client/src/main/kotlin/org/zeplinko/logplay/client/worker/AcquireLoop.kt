package org.zeplinko.logplay.client.worker

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import org.zeplinko.logplay.client.exception.JobNotOwnedByWorkerException
import org.zeplinko.logplay.client.exception.LogPlayClientException
import org.zeplinko.logplay.client.exception.ReleaseJobError
import org.zeplinko.logplay.client.exception.WorkerStoppingError
import org.zeplinko.logplay.client.handler.HandlerRegistration
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.job.JobClient
import org.zeplinko.logplay.client.model.CheckpointPage
import org.zeplinko.logplay.client.model.Job
import org.zeplinko.logplay.client.retry.RetryExecutor
import org.zeplinko.logplay.client.runtime.CheckpointReplayState
import org.zeplinko.logplay.client.runtime.CheckpointSaver
import org.zeplinko.logplay.client.runtime.JobContextImpl

internal class AcquireLoop(
    private val groupId: String,
    private val workerId: String,
    private val config: WorkerConfig,
    private val registry: HandlerRegistry,
    private val executor: HandlerExecutor,
    private val client: JobClient,
    private val saver: CheckpointSaver,
    private val mapper: ObjectMapper,
    private val retry: RetryExecutor,
    private val onWorkerLost: () -> Unit,
    private val stopRequested: () -> Boolean,
) {
    private val thread = AtomicReference<Thread?>(null)

    /** Spin up the dedicated daemon thread that runs the loop. Call exactly once. */
    fun start() {
        val t = Thread(::runLoop, "logplay-worker-$workerId-acquire").apply { isDaemon = true }
        thread.set(t)
        t.start()
    }

    /**
     * Wait up to [timeoutMs] for the loop thread to notice the stop signal ([stopRequested]) and
     * exit. The signal itself is raised by the owning worker — this only joins.
     */
    fun awaitExit(timeoutMs: Long) {
        try {
            thread.get()?.join(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runLoop() {
        var idleBackoff = config.acquireInitialBackoffMs
        while (!stopRequested()) {
            try {
                val free = executor.freeSlots()
                if (free <= 0) {
                    sleepUninterruptibly(50)
                    continue
                }
                if (registry.isEmpty()) {
                    sleepUninterruptibly(idleBackoff)
                    continue
                }
                val plan = config.acquireStrategy.plan(registry.snapshotTypes(), free)
                if (plan.isEmpty()) {
                    sleepUninterruptibly(idleBackoff)
                    continue
                }
                var totalAcquired = 0
                for (req in plan) {
                    if (stopRequested()) break
                    val cappedLimit = minOf(req.limit, config.acquireBatchLimit)
                    if (cappedLimit <= 0) continue
                    val jobs = safelyAcquire(req.type, cappedLimit) ?: continue
                    totalAcquired += jobs.size
                    for (job in jobs) {
                        if (!executor.tryReserve()) {
                            // Pool full mid-iteration — release the rest by error-reporting them.
                            log.warn(
                                "No free slots after acquire — releasing job {} (this should be rare)",
                                job.id,
                            )
                            safeRelease(job)
                            continue
                        }
                        executor.submit { runJob(job) }
                    }
                }
                if (totalAcquired == 0) {
                    sleepUninterruptibly(idleBackoff)
                    idleBackoff = (idleBackoff * 2).coerceAtMost(config.acquireMaxBackoffMs)
                } else {
                    idleBackoff = config.acquireInitialBackoffMs
                }
            } catch (e: Throwable) {
                log.error("Acquire loop iteration failed", e)
                sleepUninterruptibly(idleBackoff)
            }
        }
        log.info("Acquire loop exiting for worker {}", workerId)
    }

    private fun safelyAcquire(type: String, limit: Int): List<Job>? {
        return try {
            client.acquire(groupId, type, workerId, limit)
        } catch (e: org.zeplinko.logplay.client.exception.WorkerNotFoundException) {
            log.error("Worker {} no longer registered; stopping", workerId, e)
            onWorkerLost()
            null
        } catch (e: org.zeplinko.logplay.client.exception.WorkerCondemnedException) {
            log.error("Worker {} condemned; stopping", workerId, e)
            onWorkerLost()
            null
        } catch (e: LogPlayClientException) {
            log.warn("Acquire failed for type '{}': {}", type, e.toString())
            null
        }
    }

    private fun safeRelease(job: Job) {
        try {
            retry.run("release") { client.release(job.id, workerId) }
        } catch (e: Throwable) {
            log.warn("Release of job {} failed", job.id, e)
        } finally {
            // Note: tryReserve was not called here, so no release needed.
        }
    }

    private fun runJob(job: Job) {
        try {
            val registration =
                registry.get(job.type)
                    ?: throw IllegalStateException(
                        "No handler registered for type '${job.type}' (job ${job.id})"
                    )
            executeWithRegistration<Any?, Any?>(job, registration)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            try {
                retry.run("release") { client.release(job.id, workerId) }
            } catch (re: Throwable) {
                log.warn("Release of job {} after interruption failed", job.id, re)
            }
        } catch (e: ReleaseJobError) {
            try {
                retry.run("release") {
                    client.release(job.id, workerId, availableAt = e.wakeAtMs())
                }
            } catch (re: JobNotOwnedByWorkerException) {
                log.info(
                    "Release-with-deadline for job {} found ownership lost; next worker's replay will re-issue (wakeAt={})",
                    job.id,
                    e.wakeAtMs(),
                )
            } catch (re: Throwable) {
                log.warn(
                    "Release-with-deadline for job {} failed (wakeAt={})",
                    job.id,
                    e.wakeAtMs(),
                    re,
                )
            }
        } catch (e: WorkerStoppingError) {
            log.info("Worker stopping — releasing job {} back to PENDING", job.id)
            try {
                retry.run("release") { client.release(job.id, workerId) }
            } catch (re: JobNotOwnedByWorkerException) {
                log.info("Release-on-stop for job {} found ownership already lost", job.id)
            } catch (re: Throwable) {
                log.warn("Release-on-stop for job {} failed", job.id, re)
            }
        } catch (e: Throwable) {
            val msg = (e.message ?: e.toString()).take(8 * 1024)
            try {
                retry.run("reportError") { client.reportError(job.id, workerId, msg) }
            } catch (re: Throwable) {
                log.error("Report-error for job {} failed", job.id, re)
            }
        } finally {
            executor.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <I, O> executeWithRegistration(job: Job, registration: HandlerRegistration<*, *>) {
        val reg = registration as HandlerRegistration<I, O>
        val input: I? = reg.inputCodec.decode(job.inputData)
        val initialPage =
            try {
                client.getCheckpoints(job.id, after = null, limit = config.checkpointPageSize)
            } catch (e: LogPlayClientException) {
                throw e
            }
        val state = CheckpointReplayState(initialPage) { after -> fetchPage(job.id, after) }
        val ctx =
            JobContextImpl(
                jobId = job.id,
                workerId = workerId,
                state = state,
                saver = saver,
                mapper = mapper,
                shortSleepThresholdMs = config.shortSleepThresholdMs,
                stopRequested = stopRequested,
            )
        val handler: JobHandler<I, O> = reg.factory.create()
        // The decoded input is nullable (wire inputData is optional). Cast to I — for handlers
        // typed JobHandler<Foo, Bar> (non-null I) a null inputData triggers Kotlin's runtime null
        // check at execute() entry, surfacing the programmer error documented on JobHandler. For
        // handlers typed JobHandler<Foo?, Bar?>, null passes through unchanged.
        @Suppress("UNCHECKED_CAST") val output: O? = handler.execute(input as I, ctx)
        val outBytes: ByteArray? = reg.outputCodec.encode(output)
        retry.run("complete") { client.complete(job.id, workerId, outBytes) }
    }

    private fun fetchPage(jobId: String, after: String?): CheckpointPage =
        client.getCheckpoints(jobId, after = after, limit = config.checkpointPageSize)

    private fun sleepUninterruptibly(ms: Long) {
        if (ms <= 0) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // Benign early wake-up: an interrupt is not a stop request. Only stopRequested()
            // terminates the loop, so swallow it and let the next iteration re-check the flag.
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AcquireLoop::class.java)
    }
}
