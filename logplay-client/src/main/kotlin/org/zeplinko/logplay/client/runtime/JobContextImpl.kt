package org.zeplinko.logplay.client.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.Callable
import org.slf4j.LoggerFactory
import org.zeplinko.logplay.client.codec.Codecs
import org.zeplinko.logplay.client.codec.JacksonPayloadCodec
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.codec.TypeToken
import org.zeplinko.logplay.client.exception.ReleaseJobError
import org.zeplinko.logplay.client.exception.WorkerStoppingError
import org.zeplinko.logplay.client.handler.JobContext

internal class JobContextImpl(
    private val jobId: String,
    private val workerId: String,
    private val state: CheckpointReplayState,
    private val saver: CheckpointSaver,
    private val mapper: ObjectMapper,
    private val shortSleepThresholdMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val stopRequested: () -> Boolean,
) : JobContext {

    override fun jobId(): String = jobId

    override fun workerId(): String = workerId

    override fun <T> run(name: String, resultType: Class<T>, block: Callable<T>): T =
        run(name, JacksonPayloadCodec(mapper, resultType), block)

    override fun <T> run(name: String, resultType: TypeToken<T>, block: Callable<T>): T =
        run(name, JacksonPayloadCodec(mapper, resultType), block)

    override fun <T> run(name: String, codec: PayloadCodec<T>, block: Callable<T>): T {
        checkStopSignal()
        validateName(name)
        val replayed = state.peek()
        if (replayed != null) {
            if (replayed.name != name) {
                log.warn(
                    "Checkpoint name mismatch on replay for job {} at position {}: stored='{}', current='{}' — names are debug-only and don't affect determinism, but a mismatch may indicate the handler structure changed across executions.",
                    jobId,
                    state.cursor(),
                    replayed.name,
                    name,
                )
            }
            state.advance()
            // Codec contract permits null; T is erased here so the cast is a JVM no-op. The
            // caller's reified ctx.run<T>(...) enforces nullability at the call site.
            @Suppress("UNCHECKED_CAST")
            return codec.decode(replayed.data) as T
        }
        val result = block.call()
        val payload = codec.encode(result)
        val saved = saver.save(jobId, workerId, name, state.lastKnownId(), payload)
        state.appendNew(saved)
        state.advance()
        return result
    }

    override fun runVoid(name: String, block: Runnable) {
        run<Unit>(name, Codecs.UNIT, Callable { block.run() })
    }

    override fun sleep(name: String, duration: Duration) {
        checkStopSignal()
        require(!duration.isNegative) { "sleep duration must be non-negative" }
        validateName(name)
        val wakeAtMs: Long
        val replayed = state.peek()
        if (replayed != null) {
            if (replayed.name != name) {
                log.warn(
                    "Sleep checkpoint name mismatch on replay for job {} at position {}: stored='{}', current='{}' — names are debug-only and don't affect determinism.",
                    jobId,
                    state.cursor(),
                    replayed.name,
                    name,
                )
            }
            wakeAtMs =
                WakeAtCodec.decode(replayed.data)
                    ?: error(
                        "Sleep checkpoint at position ${state.cursor()} for job $jobId has null data — corrupted journal"
                    )
            state.advance()
        } else {
            wakeAtMs = clock() + duration.toMillis()
            val payload = WakeAtCodec.encode(wakeAtMs)
            val saved = saver.save(jobId, workerId, name, state.lastKnownId(), payload)
            state.appendNew(saved)
            state.advance()
        }
        val remainingMs = wakeAtMs - clock()
        if (remainingMs <= 0) return
        if (remainingMs <= shortSleepThresholdMs) {
            Thread.sleep(remainingMs)
            return
        }
        throw ReleaseJobError(wakeAtMs)
    }

    override fun sleep(name: String, durationMs: Long) {
        sleep(name, Duration.ofMillis(durationMs))
    }

    private fun checkStopSignal() {
        if (stopRequested()) throw WorkerStoppingError(jobId)
    }

    private fun validateName(name: String) {
        require(name.isNotBlank()) { "checkpoint name must not be blank" }
        if (state.nameAlreadyUsed(name)) {
            log.warn(
                "Checkpoint name '{}' reused in job {} — names are debug identifiers; replay still works via position, but unique names make logs and traces easier to follow.",
                name,
                jobId,
            )
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(JobContextImpl::class.java)
    }
}
