package org.zeplinko.logplay.client.worker

import org.zeplinko.logplay.client.exception.InvalidWorkerTimeoutException

/**
 * Tunable configuration for a [Worker]. Build via [WorkerConfig.builder] for Java-friendly
 * construction; defaults are exposed via [WorkerConfig.defaults].
 *
 * Field meanings:
 * - **heartbeatTimeoutMs** — declared to the server; if no heartbeat arrives within this window,
 *   the server condemns the worker and reclaims its jobs. Default `30_000`.
 * - **sessionTimeoutMs** — total worker session length (must be `> heartbeatTimeoutMs`). Default
 *   `90_000`.
 * - **heartbeatIntervalMs** — how often the SDK sends a heartbeat. Default = `heartbeatTimeoutMs /
 *   3`.
 * - **executorPoolSize** — handler thread pool size; also caps in-flight job count. Default `4`.
 * - **acquireBatchLimit** — max jobs per `POST /jobs/acquire` call. Default = [executorPoolSize].
 *   Capped at 100.
 * - **acquireInitialBackoffMs / acquireMaxBackoffMs** — backoff bounds when no jobs are pending.
 *   Defaults `200` / `5_000`.
 * - **shutdownGraceMs** — max time [Worker.stop] waits for in-flight handlers to drain. Default
 *   `30_000`.
 * - **checkpointPageSize** — page size for the lazy checkpoint replay cursor. Range `[1, 100]`,
 *   default `50`.
 * - **shortSleepThresholdMs** — `JobContext.sleep` waits in-thread (`Thread.sleep`) when the
 *   remaining wait is `<=` this value; longer sleeps release the job back to the queue with a
 *   server-side `availableAt` hint. Default `5_000`.
 * - **acquireStrategy** — pluggable per-iteration `(type, limit)` planner. Default
 *   [RoundRobinAcquireStrategy].
 */
public class WorkerConfig
private constructor(
    public val heartbeatTimeoutMs: Long,
    public val sessionTimeoutMs: Long,
    public val heartbeatIntervalMs: Long,
    public val executorPoolSize: Int,
    public val acquireBatchLimit: Int,
    public val acquireInitialBackoffMs: Long,
    public val acquireMaxBackoffMs: Long,
    public val shutdownGraceMs: Long,
    public val checkpointPageSize: Int,
    public val shortSleepThresholdMs: Long,
    public val acquireStrategy: AcquireStrategy,
) {
    /**
     * Builder for [WorkerConfig]. Validation runs at [build] time and throws either
     * `IllegalArgumentException` or
     * [org.zeplinko.logplay.client.exception.InvalidWorkerTimeoutException] for invalid
     * combinations.
     */
    public class Builder {
        private var heartbeatTimeoutMs: Long = 30_000
        private var sessionTimeoutMs: Long = 90_000
        private var heartbeatIntervalMs: Long = -1
        private var executorPoolSize: Int = 4
        private var acquireBatchLimit: Int = -1
        private var acquireInitialBackoffMs: Long = 200
        private var acquireMaxBackoffMs: Long = 5_000
        private var shutdownGraceMs: Long = 30_000
        private var checkpointPageSize: Int = 50
        private var shortSleepThresholdMs: Long = 5_000
        private var acquireStrategy: AcquireStrategy = RoundRobinAcquireStrategy()

        public fun heartbeatTimeoutMs(value: Long): Builder = apply {
            this.heartbeatTimeoutMs = value
        }

        public fun sessionTimeoutMs(value: Long): Builder = apply { this.sessionTimeoutMs = value }

        public fun heartbeatIntervalMs(value: Long): Builder = apply {
            this.heartbeatIntervalMs = value
        }

        public fun executorPoolSize(value: Int): Builder = apply { this.executorPoolSize = value }

        public fun acquireBatchLimit(value: Int): Builder = apply { this.acquireBatchLimit = value }

        public fun acquireInitialBackoffMs(value: Long): Builder = apply {
            this.acquireInitialBackoffMs = value
        }

        public fun acquireMaxBackoffMs(value: Long): Builder = apply {
            this.acquireMaxBackoffMs = value
        }

        public fun shutdownGraceMs(value: Long): Builder = apply { this.shutdownGraceMs = value }

        public fun checkpointPageSize(value: Int): Builder = apply {
            this.checkpointPageSize = value
        }

        public fun shortSleepThresholdMs(value: Long): Builder = apply {
            this.shortSleepThresholdMs = value
        }

        public fun acquireStrategy(value: AcquireStrategy): Builder = apply {
            this.acquireStrategy = value
        }

        public fun build(): WorkerConfig {
            require(heartbeatTimeoutMs > 0) { "heartbeatTimeoutMs must be > 0" }
            require(sessionTimeoutMs > 0) { "sessionTimeoutMs must be > 0" }
            if (sessionTimeoutMs <= heartbeatTimeoutMs) {
                throw InvalidWorkerTimeoutException(
                    "sessionTimeoutMs ($sessionTimeoutMs) must be > heartbeatTimeoutMs ($heartbeatTimeoutMs)"
                )
            }
            require(executorPoolSize >= 1) { "executorPoolSize must be >= 1" }
            require(acquireInitialBackoffMs >= 0) { "acquireInitialBackoffMs must be >= 0" }
            require(acquireMaxBackoffMs >= acquireInitialBackoffMs) {
                "acquireMaxBackoffMs must be >= acquireInitialBackoffMs"
            }
            require(shutdownGraceMs >= 0) { "shutdownGraceMs must be >= 0" }
            require(checkpointPageSize in 1..100) { "checkpointPageSize must be in [1, 100]" }
            require(shortSleepThresholdMs >= 0) { "shortSleepThresholdMs must be >= 0" }
            val resolvedHeartbeatInterval =
                if (heartbeatIntervalMs > 0) heartbeatIntervalMs else heartbeatTimeoutMs / 3
            val resolvedBatchLimit =
                if (acquireBatchLimit > 0) acquireBatchLimit else executorPoolSize
            require(resolvedBatchLimit in 1..100) { "acquireBatchLimit must be in [1, 100]" }
            return WorkerConfig(
                heartbeatTimeoutMs = heartbeatTimeoutMs,
                sessionTimeoutMs = sessionTimeoutMs,
                heartbeatIntervalMs = resolvedHeartbeatInterval,
                executorPoolSize = executorPoolSize,
                acquireBatchLimit = resolvedBatchLimit,
                acquireInitialBackoffMs = acquireInitialBackoffMs,
                acquireMaxBackoffMs = acquireMaxBackoffMs,
                shutdownGraceMs = shutdownGraceMs,
                checkpointPageSize = checkpointPageSize,
                shortSleepThresholdMs = shortSleepThresholdMs,
                acquireStrategy = acquireStrategy,
            )
        }
    }

    public companion object {
        /** Java-friendly entry point: `WorkerConfig.builder().heartbeatTimeoutMs(...).build()`. */
        @JvmStatic public fun builder(): Builder = Builder()

        /** Sensible defaults for typical workloads — equivalent to `builder().build()`. */
        @JvmStatic public fun defaults(): WorkerConfig = builder().build()
    }
}
