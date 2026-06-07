package org.zeplinko.logplay.client.model

import java.time.Instant

/** Lifecycle event types emitted by the server for a job. */
public enum class JobEventType {
    CREATED,
    ACQUIRED,
    RELEASED,
    COMPLETED,
    ERROR_REPORTED,
    FAILED,
    ABORTED,
}

/** Whether a [JobEvent] was triggered by a worker action or by the server itself. */
public enum class ActorType {
    WORKER,
    SYSTEM,
}

/** A single lifecycle event in a job's event stream — surfaced via `LogPlayManager.getEvents`. */
public data class JobEvent(
    val id: String,
    val jobId: String,
    val eventType: JobEventType,
    val actorType: ActorType,
    val actorId: String?,
    val createdAt: Instant,
    val eventMessage: String?,
    val eventDetail: String?,
)
