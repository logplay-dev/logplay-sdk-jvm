package org.zeplinko.logplay.client.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.zeplinko.logplay.client.exception.ReleaseJobError
import org.zeplinko.logplay.client.exception.WorkerStoppingError
import org.zeplinko.logplay.client.job.JobClient
import org.zeplinko.logplay.client.model.Checkpoint
import org.zeplinko.logplay.client.model.CheckpointPage

class JobContextImplTest {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val now: Instant = Instant.parse("2026-04-25T00:00:00Z")
    private val jobId = "j-1"
    private val workerId = "w-1"

    private fun cp(id: String, prev: String?, name: String?, dataJson: String): Checkpoint =
        Checkpoint(id, jobId, prev, name, now, dataJson.toByteArray())

    @Test
    fun `replays checkpoint and skips block execution`() {
        val initial = CheckpointPage(listOf(cp("c1", null, "step1", "\"replayed\"")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { false },
            )

        var blockExecuted = false
        val result =
            ctx.run(
                "step1",
                String::class.java,
                Callable {
                    blockExecuted = true
                    "fresh"
                },
            )

        assertThat(result).isEqualTo("replayed")
        assertThat(blockExecuted).isFalse()
    }

    @Test
    fun `executes new step and saves checkpoint`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        whenever(client.saveCheckpoint(eq(jobId), eq(workerId), eq("step1"), eq(null), any()))
            .thenReturn(cp("c1", null, "step1", "\"new\""))
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { false },
            )

        val invocations = AtomicInteger(0)
        val result =
            ctx.run(
                "step1",
                String::class.java,
                Callable {
                    invocations.incrementAndGet()
                    "new"
                },
            )

        assertThat(result).isEqualTo("new")
        assertThat(invocations.get()).isEqualTo(1)
        val captor = argumentCaptor<ByteArray>()
        verify(client)
            .saveCheckpoint(eq(jobId), eq(workerId), eq("step1"), eq(null), captor.capture())
        assertThat(String(captor.firstValue)).isEqualTo("\"new\"")
    }

    @Test
    fun `divergent name on replay logs warning and continues by position`() {
        // Stored at position 0 with name 'actualName'; replay calls with name 'expectedName'.
        // Names are debug metadata — replay should continue and decode the persisted value.
        val initial = CheckpointPage(listOf(cp("c1", null, "actualName", "\"x\"")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { false },
            )

        val result = ctx.run("expectedName", String::class.java, Callable { "fresh" })
        assertThat(result).isEqualTo("x")
    }

    @Test
    fun `run bails with WorkerStoppingError and skips block when stop requested`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { true },
            )

        var blockExecuted = false
        assertThatThrownBy {
                ctx.run(
                    "step1",
                    String::class.java,
                    Callable {
                        blockExecuted = true
                        "fresh"
                    },
                )
            }
            .isInstanceOf(WorkerStoppingError::class.java)

        assertThat(blockExecuted).isFalse()
        verify(client, org.mockito.kotlin.never())
            .saveCheckpoint(any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `stop signal gates the next boundary even on replay`() {
        // A cached checkpoint is present, but the stop signal still wins — the gate fires at the
        // next boundary regardless of replay-vs-fresh, so the job bails instead of returning "x".
        val initial = CheckpointPage(listOf(cp("c1", null, "step1", "\"x\"")), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { true },
            )

        assertThatThrownBy { ctx.run("step1", String::class.java, Callable { "fresh" }) }
            .isInstanceOf(WorkerStoppingError::class.java)
    }

    @Test
    fun `sleep bails with WorkerStoppingError when stop requested`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { true },
            )

        assertThatThrownBy { ctx.sleep("nap", Duration.ofMinutes(10)) }
            .isInstanceOf(WorkerStoppingError::class.java)
        verify(client, org.mockito.kotlin.never())
            .saveCheckpoint(any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `long sleep on fresh execution persists wake-at checkpoint and throws release`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val now = 1_000_000L
        val duration = Duration.ofMinutes(10)
        val expectedWakeAt = now + duration.toMillis()
        whenever(client.saveCheckpoint(eq(jobId), eq(workerId), eq("nap"), eq(null), any()))
            .thenReturn(cpBytes("c1", null, "nap", encodeWakeAt(expectedWakeAt)))
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                clock = { now },
                stopRequested = { false },
            )

        assertThatThrownBy { ctx.sleep("nap", duration) }
            .isInstanceOf(ReleaseJobError::class.java)
            .matches { (it as ReleaseJobError).wakeAtMs() == expectedWakeAt }

        val captor = argumentCaptor<ByteArray>()
        verify(client)
            .saveCheckpoint(eq(jobId), eq(workerId), eq("nap"), eq(null), captor.capture())
        assertThat(decodeWakeAt(captor.firstValue)).isEqualTo(expectedWakeAt)
    }

    @Test
    fun `long sleep on replay reads recorded wake-at and throws release without persisting`() {
        val originalWakeAt = 9_000_000L
        val initial =
            CheckpointPage(listOf(cpBytes("c1", null, "nap", encodeWakeAt(originalWakeAt))), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                clock = { 1_000_000L }, // remaining = 8_000_000ms — far past threshold
                stopRequested = { false },
            )

        assertThatThrownBy { ctx.sleep("nap", Duration.ofMinutes(99)) }
            .isInstanceOf(ReleaseJobError::class.java)
            .matches { (it as ReleaseJobError).wakeAtMs() == originalWakeAt }

        // Replay never writes a new checkpoint.
        verify(client, org.mockito.kotlin.never())
            .saveCheckpoint(any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `replay returns immediately when wake-at has already passed`() {
        val pastWakeAt = 500L
        val initial =
            CheckpointPage(listOf(cpBytes("c1", null, "nap", encodeWakeAt(pastWakeAt))), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                clock = { 1_000_000L },
                stopRequested = { false },
            )

        ctx.sleep("nap", Duration.ofMinutes(99)) // returns normally
        verify(client, org.mockito.kotlin.never())
            .saveCheckpoint(any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `short sleep below threshold blocks in-thread instead of throwing`() {
        // Replay path so the test doesn't write a checkpoint; remaining=50ms ≤ threshold=5000.
        val nowAtCall = 1_000_000L
        val wakeAt = nowAtCall + 50
        val initial =
            CheckpointPage(listOf(cpBytes("c1", null, "nap", encodeWakeAt(wakeAt))), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                clock = { nowAtCall },
                stopRequested = { false },
            )

        val before = System.nanoTime()
        ctx.sleep("nap", Duration.ofMinutes(99)) // returns after Thread.sleep(~50ms)
        val elapsedMs = (System.nanoTime() - before) / 1_000_000
        assertThat(elapsedMs).isGreaterThanOrEqualTo(40) // wall-clock slack
    }

    @Test
    fun `negative duration is rejected on first execution`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { false },
            )

        assertThatThrownBy { ctx.sleep("nap", Duration.ofSeconds(-1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun cpBytes(id: String, prev: String?, name: String?, data: ByteArray): Checkpoint =
        Checkpoint(id, jobId, prev, name, now, data)

    private fun encodeWakeAt(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

    private fun decodeWakeAt(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).long

    @Test
    fun `duplicate name within execution logs warning and saves another checkpoint`() {
        val initial = CheckpointPage(emptyList(), false)
        val state = CheckpointReplayState(initial) { error("no") }
        val client: JobClient = mock()
        whenever(
                client.saveCheckpoint(eq(jobId), eq(workerId), eq("dup"), anyOrNull(), anyOrNull())
            )
            .thenReturn(cp("c1", null, "dup", "\"v1\""))
            .thenReturn(cp("c2", "c1", "dup", "\"v2\""))
        val ctx =
            JobContextImpl(
                jobId,
                workerId,
                state,
                CheckpointSaver(client),
                mapper,
                shortSleepThresholdMs = 5_000,
                stopRequested = { false },
            )

        // Both calls succeed — duplicate name no longer fails.
        val r1 = ctx.run("dup", String::class.java, Callable { "v1" })
        val r2 = ctx.run("dup", String::class.java, Callable { "v2" })
        assertThat(r1).isEqualTo("v1")
        assertThat(r2).isEqualTo("v2")
    }
}
