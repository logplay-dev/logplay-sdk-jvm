package org.zeplinko.logplay.client.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.retry.Retryable

class ExceptionHierarchyTest {

    // --- Category parents -----------------------------------------------------------------------

    @Test
    fun `validation exceptions are LogPlayClientException`() {
        val e: LogPlayClientException = BlankGroupIdException()
        assertThat(e).isInstanceOf(LogPlayValidationException::class.java)
    }

    @Test
    fun `not-found exceptions share a category parent`() {
        assertThat(JobNotFoundException("j-1")).isInstanceOf(NotFoundException::class.java)
        assertThat(WorkerNotFoundException("w-1")).isInstanceOf(NotFoundException::class.java)
        assertThat(CheckpointNotFoundException("c-1")).isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `condemned worker is forbidden`() {
        assertThat(WorkerCondemnedException("w-1")).isInstanceOf(ForbiddenException::class.java)
    }

    @Test
    fun `conflict exceptions share parent`() {
        assertThat(DuplicateIdempotencyKeyException("g", "k"))
            .isInstanceOf(ConflictException::class.java)
        assertThat(WorkerAlreadyRegisteredException("w"))
            .isInstanceOf(ConflictException::class.java)
        assertThat(JobNotAcquiredException("j", "msg")).isInstanceOf(ConflictException::class.java)
        assertThat(JobNotAbortableException("j", "msg")).isInstanceOf(ConflictException::class.java)
        assertThat(JobNotOwnedByWorkerException("j", "w"))
            .isInstanceOf(ConflictException::class.java)
        assertThat(InvalidCheckpointOrderException("j", "msg"))
            .isInstanceOf(ConflictException::class.java)
        assertThat(JobConcurrentModificationException("j"))
            .isInstanceOf(ConflictException::class.java)
    }

    // --- Retryable contract ---------------------------------------------------------------------

    @Test
    fun `transport and concurrent-modification exceptions are retryable`() {
        assertThat(LogPlayTransportException("io")).isInstanceOf(Retryable::class.java)
        assertThat(JobConcurrentModificationException("j")).isInstanceOf(Retryable::class.java)
    }

    @Test
    fun `non-retryable exceptions do NOT implement Retryable`() {
        // Pin down the negative — accidental Retryable additions would silently change retry
        // behavior in the worker runtime.
        assertThat(BlankGroupIdException()).isNotInstanceOf(Retryable::class.java)
        assertThat(JobNotFoundException("j")).isNotInstanceOf(Retryable::class.java)
        assertThat(WorkerNotFoundException("w")).isNotInstanceOf(Retryable::class.java)
        assertThat(WorkerCondemnedException("w")).isNotInstanceOf(Retryable::class.java)
        assertThat(DuplicateIdempotencyKeyException("g", "k"))
            .isNotInstanceOf(Retryable::class.java)
        assertThat(JobNotAcquiredException("j", "msg")).isNotInstanceOf(Retryable::class.java)
        assertThat(JobNotAbortableException("j", "msg")).isNotInstanceOf(Retryable::class.java)
        assertThat(InvalidCheckpointOrderException("j", "msg"))
            .isNotInstanceOf(Retryable::class.java)
        assertThat(LogPlayServerException(500, "x")).isNotInstanceOf(Retryable::class.java)
        assertThat(CheckpointDivergenceException("j", 0, "a", "b"))
            .isNotInstanceOf(Retryable::class.java)
    }

    // --- Server / transport / generic envelopes -------------------------------------------------

    @Test
    fun `server fallback carries http status`() {
        val e = LogPlayServerException(503, "Service Unavailable")
        assertThat(e.httpStatus).isEqualTo(503)
        assertThat(e.message).contains("503").contains("Service Unavailable")
    }

    @Test
    fun `transport exception preserves cause`() {
        val cause = java.io.IOException("connection refused")
        val e = LogPlayTransportException("network blip", cause)
        assertThat(e.message).isEqualTo("network blip")
        assertThat(e.cause).isSameAs(cause)
    }

    @Test
    fun `LogPlayClientException no-cause overload sets cause to null`() {
        val e = LogPlayClientException("just a message")
        assertThat(e.cause).isNull()
        assertThat(e.message).isEqualTo("just a message")
    }

    // --- Field carriers preserve their fields ---------------------------------------------------

    @Test
    fun `JobNotFoundException carries jobId`() {
        val e = JobNotFoundException("job-42")
        assertThat(e.jobId).isEqualTo("job-42")
        assertThat(e.message).contains("job-42")
    }

    @Test
    fun `WorkerCondemnedException carries workerId`() {
        val e = WorkerCondemnedException("w-7")
        assertThat(e.workerId).isEqualTo("w-7")
        assertThat(e.message).contains("w-7")
    }

    @Test
    fun `DuplicateIdempotencyKeyException carries groupId and key`() {
        val e = DuplicateIdempotencyKeyException("emails", "k-1")
        assertThat(e.groupId).isEqualTo("emails")
        assertThat(e.key).isEqualTo("k-1")
        assertThat(e.message).contains("emails").contains("k-1")
    }

    @Test
    fun `JobNotOwnedByWorkerException carries jobId and workerId`() {
        val e = JobNotOwnedByWorkerException("job-1", "worker-1")
        assertThat(e.jobId).isEqualTo("job-1")
        assertThat(e.workerId).isEqualTo("worker-1")
        assertThat(e.message).contains("job-1").contains("worker-1")
    }

    @Test
    fun `CheckpointDivergenceException carries jobId, cursor, expected, actual`() {
        val e = CheckpointDivergenceException("job-1", 3, "step-a", "step-b")
        assertThat(e.jobId).isEqualTo("job-1")
        assertThat(e.cursor).isEqualTo(3)
        assertThat(e.expected).isEqualTo("step-a")
        assertThat(e.actual).isEqualTo("step-b")
        assertThat(e.message).contains("job-1").contains("3").contains("step-a").contains("step-b")
    }

    @Test
    fun `validation default-message exceptions use their default text`() {
        assertThat(BlankGroupIdException().message).contains("groupId")
        assertThat(BlankJobTypeException().message).contains("type")
        assertThat(BlankJobIdException().message).contains("jobId")
        assertThat(BlankWorkerIdException().message).contains("workerId")
        assertThat(BlankIdempotencyKeyException().message).contains("idempotencyKey")
        assertThat(InvalidMaxRetriesException().message).contains("maxRetries")
    }
}
