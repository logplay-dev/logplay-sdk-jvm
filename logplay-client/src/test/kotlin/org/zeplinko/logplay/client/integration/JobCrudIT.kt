package org.zeplinko.logplay.client.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.exception.DuplicateIdempotencyKeyException
import org.zeplinko.logplay.client.exception.JobNotAbortableException
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.model.JobStatus

class JobCrudIT : AbstractIntegrationTest() {

    @Test
    fun `createJob returns PENDING and CREATED event is recorded`() {
        val type = uniqueType()
        val req = CreateJobRequest.builder().type(type).idempotencyKey(uniqueKey()).build()
        val job = manager.createJob(req)

        assertThat(job.status).isEqualTo(JobStatus.PENDING)
        assertThat(job.groupId).isEqualTo(groupId)
        assertThat(job.type).isEqualTo(type)

        val events = manager.getEvents(job.id)
        assertThat(events.map { it.eventType }).contains(JobEventType.CREATED)
    }

    @Test
    fun `createJob with same idempotencyKey throws DuplicateIdempotencyKeyException`() {
        val type = uniqueType()
        val key = uniqueKey()
        val req = CreateJobRequest.builder().type(type).idempotencyKey(key).build()
        manager.createJob(req)

        assertThatThrownBy { manager.createJob(req) }
            .isInstanceOf(DuplicateIdempotencyKeyException::class.java)
    }

    @Test
    fun `abortJob transitions a PENDING job to ABORTED`() {
        val req = CreateJobRequest.builder().type(uniqueType()).idempotencyKey(uniqueKey()).build()
        val job = manager.createJob(req)
        val aborted = manager.abortJob(job.id)
        assertThat(aborted.status).isEqualTo(JobStatus.ABORTED)

        // Aborting an already-aborted job is rejected by the server.
        assertThatThrownBy { manager.abortJob(job.id) }
            .isInstanceOf(JobNotAbortableException::class.java)
    }

    @Test
    fun `createJob with groupIdOverride writes into the override group, not the manager default`() {
        val overrideGroup = uniqueGroupId("override")
        val req = CreateJobRequest.builder().type(uniqueType()).idempotencyKey(uniqueKey()).build()

        val job = manager.createJob(req, overrideGroup)

        assertThat(job.groupId).isEqualTo(overrideGroup)
        assertThat(job.groupId).isNotEqualTo(manager.groupId())
    }

    @Test
    fun `createJob assigns a server-side name when name is omitted`() {
        val req = CreateJobRequest.builder().type(uniqueType()).idempotencyKey(uniqueKey()).build()
        val job = manager.createJob(req)
        assertThat(job.name).isNotBlank
        assertThat(job.id).isNotBlank
        assertThat(job.type).isEqualTo(req.type)
    }

    @Test
    fun `createJob with explicit name uses the supplied name`() {
        val req =
            CreateJobRequest.builder()
                .type(uniqueType())
                .idempotencyKey(uniqueKey())
                .name("user-friendly-name")
                .build()
        val job = manager.createJob(req)
        assertThat(job.name).isEqualTo("user-friendly-name")
    }
}
