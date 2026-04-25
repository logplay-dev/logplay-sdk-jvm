package org.zeplinko.logplay.client.worker

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.zeplinko.logplay.client.codec.Codecs
import org.zeplinko.logplay.client.exception.WorkerCondemnedException
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.job.JobClient
import org.zeplinko.logplay.client.retry.ExponentialBackoffRetryPolicy

/**
 * Unit tests for [Worker.stop] / self-stop behavior, driven by a mocked [JobClient] (no server).
 * The class-level [Timeout] is a backstop: a regression that deadlocks shutdown fails fast here
 * instead of hanging the suite.
 */
@Timeout(10)
class WorkerStopTest {
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    // Small timings so the acquire/heartbeat loops wind down quickly under test.
    private fun fastConfig(): WorkerConfig =
        WorkerConfig.builder()
            .heartbeatTimeoutMs(1_000)
            .sessionTimeoutMs(2_000)
            .acquireInitialBackoffMs(10)
            .acquireMaxBackoffMs(20)
            .shutdownGraceMs(1_000)
            .build()

    private fun newWorker(client: JobClient): Worker =
        Worker("g", "w-1", fastConfig(), client, mapper, ExponentialBackoffRetryPolicy())

    @Test
    fun `stop on a never-started worker terminates cleanly`() {
        val client: JobClient = mock()
        val worker = newWorker(client)

        worker.stop() // must return (not hang) even though start() was never called

        assertThat(worker.isRunning()).isFalse()
        assertThat(worker.awaitTermination(0)).isTrue() // already stopped → returns immediately
    }

    @Test
    fun `start then stop transitions to stopped and deregisters`() {
        val client: JobClient = mock()
        val worker = newWorker(client)

        worker.start()
        assertThat(worker.isRunning()).isTrue()

        worker.stop()

        assertThat(worker.isRunning()).isFalse()
        // stop() blocks until teardown completes, so the DELETE has already happened on return.
        verify(client).deregisterWorker("w-1")
    }

    @Test
    fun `stop is idempotent`() {
        val client: JobClient = mock()
        val worker = newWorker(client)
        worker.start()

        worker.stop()
        worker.stop() // second call short-circuits (already STOPPED)

        assertThat(worker.isRunning()).isFalse()
        verify(client, times(1)).deregisterWorker("w-1")
    }

    @Test
    fun `awaitTermination returns once the worker is stopped`() {
        val client: JobClient = mock()
        val worker = newWorker(client)
        worker.start()
        worker.stop()

        worker.awaitTermination() // returns immediately; would hang if the latch never fired
        assertThat(worker.awaitTermination(0)).isTrue()
    }

    @Test
    fun `bounded awaitTermination times out while the worker is running`() {
        val client: JobClient = mock()
        val worker = newWorker(client)
        worker.start()
        try {
            assertThat(worker.awaitTermination(50)).isFalse()
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `concurrent stop calls all block until stopped and tear down exactly once`() {
        val client: JobClient = mock()
        // Widen the teardown window so the other callers genuinely park on the latch mid-shutdown.
        doAnswer {
                Thread.sleep(50)
                null
            }
            .whenever(client)
            .deregisterWorker("w-1")
        val worker = newWorker(client)
        worker.start()

        val threadCount = 8
        val atBarrier = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val observedStopped = AtomicInteger(0)
        val errors = ConcurrentLinkedQueue<Throwable>()

        val callers =
            (1..threadCount).map {
                Thread {
                        try {
                            atBarrier.countDown()
                            go.await()
                            worker.stop() // blocks until fully stopped
                            if (!worker.isRunning()) observedStopped.incrementAndGet()
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                    .apply { start() }
            }

        atBarrier.await() // all callers staged
        go.countDown() // release them together
        callers.forEach { it.join(5_000) }

        assertThat(errors).isEmpty()
        assertThat(callers.any { it.isAlive }).isFalse() // none hung
        assertThat(worker.isRunning()).isFalse()
        // Every caller returned only after observing STOPPED — stop() blocks until done for all.
        assertThat(observedStopped.get()).isEqualTo(threadCount)
        // Exactly one teardown despite N concurrent callers (the CAS guard in stopAsync).
        verify(client, times(1)).deregisterWorker("w-1")
    }

    @Test
    fun `concurrent start and stop never zombie or hang`() {
        // Race start() against stop() from two threads, many times. The lifecycle lock must make
        // every interleaving end cleanly: stop() always returns (no hang) and leaves the worker
        // STOPPED (no RUNNING zombie). Without the lock this fails on some iterations.
        repeat(50) {
            val client: JobClient = mock()
            val worker = newWorker(client)
            val errors = ConcurrentLinkedQueue<Throwable>()

            val starter = Thread {
                try {
                    worker.start()
                } catch (_: IllegalStateException) {
                    // A stop that won the NEW state makes start() a no-op error — allowed.
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
            val stopper = Thread {
                try {
                    worker.stop()
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
            starter.start()
            stopper.start()
            starter.join(2_000)
            stopper.join(2_000)

            assertThat(starter.isAlive).isFalse()
            assertThat(stopper.isAlive).isFalse()
            assertThat(errors).isEmpty()
            // stop() blocks until done, so once it returns the worker is STOPPED — never a RUNNING
            // zombie left behind by a racing start().
            assertThat(worker.isRunning()).isFalse()
        }
    }

    @Test
    fun `stop still reaches STOPPED when deregister fails`() {
        val client: JobClient = mock()
        doThrow(RuntimeException("deregister boom")).whenever(client).deregisterWorker("w-1")
        val worker = newWorker(client)
        worker.start()

        worker.stop() // the failing DELETE is logged, not propagated

        assertThat(worker.isRunning()).isFalse()
        assertThat(worker.awaitTermination(0)).isTrue() // still released
        verify(client).deregisterWorker("w-1")
    }

    @Test
    fun `stop releases waiters even if an unguarded teardown step throws`() {
        val client: JobClient = mock()
        val worker = newWorker(client)
        worker.start()

        // Replace the heartbeat with one whose stop() throws. That call sits *outside* the inner
        // deregister guard, so without the finally in runShutdown the latch would never fire and
        // stop() (which is uninterruptible) would hang — caught here by the class @Timeout.
        val boom = mock<HeartbeatLoop>()
        doThrow(RuntimeException("heartbeat stop boom")).whenever(boom).stop()
        heartbeatRef(worker).set(boom)

        worker.stop()

        assertThat(worker.isRunning()).isFalse()
        assertThat(worker.awaitTermination(0)).isTrue() // latch fired despite the teardown failure
    }

    @Test
    fun `worker self-stops when the server condemns it`() {
        val client: JobClient = mock()
        whenever(client.acquire(any(), any(), any(), any()))
            .thenThrow(WorkerCondemnedException("w-1"))
        val worker = newWorker(client)
        // A handler is required for the acquire loop to actually poll (and hit the condemn).
        worker.registerHandler(
            "t",
            Codecs.UTF8,
            Codecs.UTF8,
            JobHandler<String, String> { i, _ -> i },
        )

        worker.start()

        // The acquire thread fires onWorkerLost -> stopAsync -> runShutdown off a separate thread;
        // if that path self-deadlocked, this await would time out and the assertion would fail.
        assertThat(worker.awaitTermination(2_000)).isTrue()
        assertThat(worker.isRunning()).isFalse()
        verify(client).deregisterWorker("w-1")
    }

    // The `heartbeat` collaborator is constructed internally and private; reflection is the only
    // seam to inject a fault into an *unguarded* teardown step for the hardening test above.
    @Suppress("UNCHECKED_CAST")
    private fun heartbeatRef(worker: Worker): AtomicReference<HeartbeatLoop?> =
        Worker::class.java.getDeclaredField("heartbeat").apply { isAccessible = true }.get(worker)
            as AtomicReference<HeartbeatLoop?>
}
