package org.zeplinko.logplay.client.model

import java.time.Instant

/**
 * Server-side view of a registered worker. Returned by the worker registration / heartbeat
 * endpoints. Surfaced indirectly via the worker runtime; users typically don't construct or inspect
 * this directly.
 */
public data class WorkerInfo(
    val id: String,
    val heartbeatTimeoutMs: Long,
    val sessionTimeoutMs: Long,
    val lastHeartbeatAt: Instant,
    val registeredAt: Instant,
)
