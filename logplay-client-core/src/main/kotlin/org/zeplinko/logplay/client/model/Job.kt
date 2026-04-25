package org.zeplinko.logplay.client.model

import java.time.Instant

/**
 * Server-side view of a job. Returned by `LogPlayManager.createJob`, `abortJob`, and the worker
 * runtime when it acquires a job for execution.
 *
 * `inputData` and `outputData` are nullable — null means the field was not set on the wire (vs.
 * empty bytes, which is a non-null payload of length zero). `version` is the optimistic-concurrency
 * token; the server bumps it on every state change.
 */
public data class Job(
    val id: String,
    val groupId: String,
    val name: String,
    val type: String,
    val status: JobStatus,
    val retries: Int,
    val maxRetries: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastAcquiredAt: Instant?,
    val acquiredByWorkerId: String?,
    val inputData: ByteArray?,
    val outputData: ByteArray?,
    val version: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Job) return false
        return id == other.id &&
            groupId == other.groupId &&
            name == other.name &&
            type == other.type &&
            status == other.status &&
            retries == other.retries &&
            maxRetries == other.maxRetries &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            lastAcquiredAt == other.lastAcquiredAt &&
            acquiredByWorkerId == other.acquiredByWorkerId &&
            (inputData?.contentEquals(other.inputData) ?: (other.inputData == null)) &&
            (outputData?.contentEquals(other.outputData) ?: (other.outputData == null)) &&
            version == other.version
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + groupId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + retries
        result = 31 * result + (maxRetries ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + (lastAcquiredAt?.hashCode() ?: 0)
        result = 31 * result + (acquiredByWorkerId?.hashCode() ?: 0)
        result = 31 * result + (inputData?.contentHashCode() ?: 0)
        result = 31 * result + (outputData?.contentHashCode() ?: 0)
        result = 31 * result + version.hashCode()
        return result
    }
}
