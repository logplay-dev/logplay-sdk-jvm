package org.zeplinko.logplay.client.job

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
import org.zeplinko.logplay.client.dto.AcquireJobsRequestDto
import org.zeplinko.logplay.client.dto.CheckpointPageResponseDto
import org.zeplinko.logplay.client.dto.CheckpointResponseDto
import org.zeplinko.logplay.client.dto.CompleteJobRequestDto
import org.zeplinko.logplay.client.dto.JobEventResponseDto
import org.zeplinko.logplay.client.dto.JobResponseDto
import org.zeplinko.logplay.client.dto.RegisterWorkerRequestDto
import org.zeplinko.logplay.client.dto.ReleaseJobRequestDto
import org.zeplinko.logplay.client.dto.ReportExecutionErrorRequestDto
import org.zeplinko.logplay.client.dto.SaveCheckpointRequestDto
import org.zeplinko.logplay.client.dto.WorkerResponseDto
import org.zeplinko.logplay.client.dto.toDomain
import org.zeplinko.logplay.client.dto.toDto
import org.zeplinko.logplay.client.http.HttpRoutes
import org.zeplinko.logplay.client.http.HttpTransport
import org.zeplinko.logplay.client.model.Checkpoint
import org.zeplinko.logplay.client.model.CheckpointPage
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.Job
import org.zeplinko.logplay.client.model.JobEvent
import org.zeplinko.logplay.client.model.WorkerInfo

internal class JobClient(
    private val baseUrl: String,
    private val transport: HttpTransport,
    private val mapper: ObjectMapper,
) {

    fun <I> createJob(groupId: String, request: CreateJobRequest<I>): Job =
        transport
            .post(
                HttpRoutes.jobs(baseUrl),
                request.toDto(groupId, mapper),
                JobResponseDto::class.java,
            )
            .toDomain()

    fun acquire(groupId: String, type: String, workerId: String, limit: Int): List<Job> {
        val body =
            AcquireJobsRequestDto(
                groupId = groupId,
                type = type,
                workerId = workerId,
                limit = limit,
            )
        val list: List<JobResponseDto> =
            transport.post(
                HttpRoutes.acquireJobs(baseUrl),
                body,
                object : TypeReference<List<JobResponseDto>>() {},
            )
        return list.map { it.toDomain() }
    }

    fun complete(jobId: String, workerId: String, outputData: ByteArray?): Job {
        val body =
            CompleteJobRequestDto(
                workerId = workerId,
                outputData = outputData?.let { Base64.getEncoder().encodeToString(it) },
            )
        return transport
            .post(HttpRoutes.complete(baseUrl, jobId), body, JobResponseDto::class.java)
            .toDomain()
    }

    fun release(jobId: String, workerId: String, availableAt: Long? = null): Job =
        transport
            .post(
                HttpRoutes.release(baseUrl, jobId),
                ReleaseJobRequestDto(workerId, availableAt),
                JobResponseDto::class.java,
            )
            .toDomain()

    fun reportError(jobId: String, workerId: String, error: String?): Job =
        transport
            .post(
                HttpRoutes.reportError(baseUrl, jobId),
                ReportExecutionErrorRequestDto(workerId, error),
                JobResponseDto::class.java,
            )
            .toDomain()

    fun abort(jobId: String): Job =
        transport
            .post(HttpRoutes.abort(baseUrl, jobId), null, JobResponseDto::class.java)
            .toDomain()

    fun getCheckpoints(jobId: String, after: String?, limit: Int?): CheckpointPage =
        transport
            .get(
                HttpRoutes.checkpoints(baseUrl, jobId, after, limit),
                CheckpointPageResponseDto::class.java,
            )
            .toDomain()

    fun saveCheckpoint(
        jobId: String,
        workerId: String,
        name: String?,
        previousCheckpointId: String?,
        data: ByteArray?,
    ): Checkpoint {
        val body =
            SaveCheckpointRequestDto(
                workerId = workerId,
                name = name,
                previousCheckpointId = previousCheckpointId,
                data = data?.let { Base64.getEncoder().encodeToString(it) },
            )
        return transport
            .post(
                HttpRoutes.saveCheckpoint(baseUrl, jobId),
                body,
                CheckpointResponseDto::class.java,
            )
            .toDomain()
    }

    fun getEvents(jobId: String): List<JobEvent> {
        val list: List<JobEventResponseDto> =
            transport.get(
                HttpRoutes.events(baseUrl, jobId),
                object : TypeReference<List<JobEventResponseDto>>() {},
            )
        return list.map { it.toDomain() }
    }

    fun registerWorker(
        workerId: String,
        heartbeatTimeoutMs: Long,
        sessionTimeoutMs: Long,
    ): WorkerInfo {
        val body =
            RegisterWorkerRequestDto(
                workerId = workerId,
                heartbeatTimeout = heartbeatTimeoutMs,
                sessionTimeout = sessionTimeoutMs,
            )
        return transport
            .post(HttpRoutes.workers(baseUrl), body, WorkerResponseDto::class.java)
            .toDomain()
    }

    fun heartbeat(workerId: String): WorkerInfo =
        transport
            .post(HttpRoutes.heartbeat(baseUrl, workerId), null, WorkerResponseDto::class.java)
            .toDomain()

    fun deregisterWorker(workerId: String) {
        transport.delete(HttpRoutes.worker(baseUrl, workerId))
    }
}
