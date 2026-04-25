package org.zeplinko.logplay.client.worker

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.exception.InvalidWorkerTimeoutException

class WorkerConfigBuilderTest {

    @Test
    fun `defaults are sensible`() {
        val cfg = WorkerConfig.defaults()
        assertThat(cfg.heartbeatTimeoutMs).isPositive
        assertThat(cfg.sessionTimeoutMs).isGreaterThan(cfg.heartbeatTimeoutMs)
        assertThat(cfg.heartbeatIntervalMs).isPositive
        assertThat(cfg.executorPoolSize).isGreaterThanOrEqualTo(1)
        assertThat(cfg.acquireBatchLimit).isGreaterThanOrEqualTo(1)
        assertThat(cfg.checkpointPageSize).isBetween(1, 100)
        assertThat(cfg.shortSleepThresholdMs).isEqualTo(5_000)
        assertThat(cfg.acquireStrategy).isInstanceOf(RoundRobinAcquireStrategy::class.java)
    }

    @Test
    fun `shortSleepThresholdMs override is respected and validated`() {
        val cfg = WorkerConfig.builder().shortSleepThresholdMs(10_000).build()
        assertThat(cfg.shortSleepThresholdMs).isEqualTo(10_000)
        assertThatThrownBy { WorkerConfig.builder().shortSleepThresholdMs(-1).build() }
            .hasMessageContaining("shortSleepThresholdMs")
    }

    @Test
    fun `session must be greater than heartbeat`() {
        assertThatThrownBy {
                WorkerConfig.builder().heartbeatTimeoutMs(10_000).sessionTimeoutMs(10_000).build()
            }
            .isInstanceOf(InvalidWorkerTimeoutException::class.java)
    }

    @Test
    fun `pool size and batch limits are validated`() {
        assertThatThrownBy { WorkerConfig.builder().executorPoolSize(0).build() }
            .hasMessageContaining("executorPoolSize")
        assertThatThrownBy { WorkerConfig.builder().acquireBatchLimit(101).build() }
            .hasMessageContaining("acquireBatchLimit")
        assertThatThrownBy { WorkerConfig.builder().checkpointPageSize(101).build() }
            .hasMessageContaining("checkpointPageSize")
    }

    @Test
    fun `heartbeatInterval defaults to a third of timeout`() {
        val cfg = WorkerConfig.builder().heartbeatTimeoutMs(30_000).sessionTimeoutMs(90_000).build()
        assertThat(cfg.heartbeatIntervalMs).isEqualTo(10_000)
    }

    @Test
    fun `explicit heartbeatInterval is respected`() {
        val cfg =
            WorkerConfig.builder()
                .heartbeatTimeoutMs(30_000)
                .sessionTimeoutMs(90_000)
                .heartbeatIntervalMs(5_000)
                .build()
        assertThat(cfg.heartbeatIntervalMs).isEqualTo(5_000)
    }

    @Test
    fun `acquireBatchLimit defaults to executorPoolSize when not set`() {
        val cfg = WorkerConfig.builder().executorPoolSize(7).build()
        assertThat(cfg.acquireBatchLimit).isEqualTo(7)
    }

    @Test
    fun `explicit acquireBatchLimit overrides default`() {
        val cfg = WorkerConfig.builder().executorPoolSize(7).acquireBatchLimit(3).build()
        assertThat(cfg.acquireBatchLimit).isEqualTo(3)
    }

    @Test
    fun `heartbeatTimeout and sessionTimeout must be positive`() {
        assertThatThrownBy { WorkerConfig.builder().heartbeatTimeoutMs(0).build() }
            .hasMessageContaining("heartbeatTimeoutMs")
        assertThatThrownBy {
                WorkerConfig.builder().sessionTimeoutMs(0).heartbeatTimeoutMs(1).build()
            }
            .hasMessageContaining("sessionTimeoutMs")
    }

    @Test
    fun `acquire backoff bounds must be ordered and non-negative`() {
        assertThatThrownBy { WorkerConfig.builder().acquireInitialBackoffMs(-1).build() }
            .hasMessageContaining("acquireInitialBackoffMs")
        assertThatThrownBy {
                WorkerConfig.builder()
                    .acquireInitialBackoffMs(1_000)
                    .acquireMaxBackoffMs(500)
                    .build()
            }
            .hasMessageContaining("acquireMaxBackoffMs")
    }

    @Test
    fun `shutdownGraceMs must be non-negative`() {
        assertThatThrownBy { WorkerConfig.builder().shutdownGraceMs(-1).build() }
            .hasMessageContaining("shutdownGraceMs")
    }

    @Test
    fun `checkpointPageSize lower bound is enforced`() {
        assertThatThrownBy { WorkerConfig.builder().checkpointPageSize(0).build() }
            .hasMessageContaining("checkpointPageSize")
    }

    @Test
    fun `acquireStrategy is preserved when overridden`() {
        val custom =
            object : AcquireStrategy {
                override fun plan(
                    types: List<String>,
                    freeSlots: Int,
                ): List<AcquireStrategy.TypeAcquireRequest> = emptyList()
            }
        val cfg = WorkerConfig.builder().acquireStrategy(custom).build()
        assertThat(cfg.acquireStrategy).isSameAs(custom)
    }

    @Test
    fun `defaults() and builder() build() produce equal configs`() {
        val a = WorkerConfig.defaults()
        val b = WorkerConfig.builder().build()
        // Spot-check rather than data-class equals since WorkerConfig is not a data class.
        assertThat(a.heartbeatTimeoutMs).isEqualTo(b.heartbeatTimeoutMs)
        assertThat(a.sessionTimeoutMs).isEqualTo(b.sessionTimeoutMs)
        assertThat(a.executorPoolSize).isEqualTo(b.executorPoolSize)
        assertThat(a.checkpointPageSize).isEqualTo(b.checkpointPageSize)
    }
}
