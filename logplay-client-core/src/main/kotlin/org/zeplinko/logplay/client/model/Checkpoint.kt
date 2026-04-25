package org.zeplinko.logplay.client.model

import java.time.Instant

/**
 * One persisted checkpoint in a job's journal. Produced by `ctx.run(name, ...)` inside a handler.
 *
 * `name` is debug metadata — replay determinism is position-based. `data` is nullable; the codec's
 * `decode(bytes)` is what restores the user's value (a null result on encode side round-trips to
 * null on decode).
 */
public data class Checkpoint(
    val id: String,
    val jobId: String,
    val previousCheckpointId: String?,
    val name: String?,
    val createdAt: Instant,
    val data: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Checkpoint) return false
        return id == other.id &&
            jobId == other.jobId &&
            previousCheckpointId == other.previousCheckpointId &&
            name == other.name &&
            createdAt == other.createdAt &&
            (data?.contentEquals(other.data) ?: (other.data == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + jobId.hashCode()
        result = 31 * result + (previousCheckpointId?.hashCode() ?: 0)
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + (data?.contentHashCode() ?: 0)
        return result
    }
}
