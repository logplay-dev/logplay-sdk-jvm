package org.zeplinko.logplay.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.exception.BlankGroupIdException
import org.zeplinko.logplay.client.exception.ConflictException
import org.zeplinko.logplay.client.exception.DuplicateIdempotencyKeyException
import org.zeplinko.logplay.client.exception.JobConcurrentModificationException
import org.zeplinko.logplay.client.exception.JobNotFoundException
import org.zeplinko.logplay.client.exception.LogPlayServerException
import org.zeplinko.logplay.client.exception.NotFoundException
import org.zeplinko.logplay.client.exception.WorkerCondemnedException
import org.zeplinko.logplay.client.exception.WorkerNotFoundException

class ErrorMapperTest {
    private val mapper = ObjectMapper()
    private val em = ErrorMapper(mapper)

    private fun body(error: String): ByteArray = """{"error":"$error"}""".toByteArray()

    @Test
    fun `400 blank groupId maps to BlankGroupIdException`() {
        val ex = em.translate(400, body("groupId must not be blank"))
        assertThat(ex).isInstanceOf(BlankGroupIdException::class.java)
    }

    @Test
    fun `404 worker maps to WorkerNotFoundException`() {
        val ex = em.translate(404, body("Worker not found: w-123"))
        assertThat(ex).isInstanceOf(WorkerNotFoundException::class.java)
    }

    @Test
    fun `404 job maps to JobNotFoundException`() {
        val ex = em.translate(404, body("Job not found: job-abc"))
        assertThat(ex).isInstanceOf(JobNotFoundException::class.java)
    }

    @Test
    fun `403 condemned maps to WorkerCondemnedException`() {
        val ex = em.translate(403, body("Worker condemned: w-1"))
        assertThat(ex).isInstanceOf(WorkerCondemnedException::class.java)
    }

    @Test
    fun `409 duplicate maps to DuplicateIdempotencyKeyException`() {
        val ex = em.translate(409, body("Duplicate idempotency key 'k-1' in group 'g-2'"))
        assertThat(ex).isInstanceOf(DuplicateIdempotencyKeyException::class.java)
    }

    @Test
    fun `409 concurrent maps to JobConcurrentModificationException`() {
        val ex = em.translate(409, body("Concurrent modification on job job-xyz"))
        assertThat(ex).isInstanceOf(JobConcurrentModificationException::class.java)
    }

    @Test
    fun `409 generic conflict falls back to ConflictException`() {
        val ex = em.translate(409, body("Some unmapped conflict"))
        assertThat(ex).isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `404 generic falls back to NotFoundException`() {
        val ex = em.translate(404, body("Unknown resource"))
        assertThat(ex).isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `500 falls back to LogPlayServerException`() {
        val ex = em.translate(500, body("internal explosion"))
        assertThat(ex).isInstanceOf(LogPlayServerException::class.java)
        assertThat((ex as LogPlayServerException).httpStatus).isEqualTo(500)
    }

    @Test
    fun `non-JSON body still produces typed exception`() {
        val ex = em.translate(404, "<html>not found</html>".toByteArray())
        assertThat(ex).isInstanceOf(NotFoundException::class.java)
    }
}
