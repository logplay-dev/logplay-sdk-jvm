package org.zeplinko.logplay.client.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.Base64
import org.zeplinko.logplay.client.codec.JacksonPayloadCodec
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.model.ActorType
import org.zeplinko.logplay.client.model.Checkpoint
import org.zeplinko.logplay.client.model.CheckpointPage
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.InputSource
import org.zeplinko.logplay.client.model.Job
import org.zeplinko.logplay.client.model.JobEvent
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.model.JobStatus

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CreateJobRequestDto(
    val groupId: String,
    val type: String,
    val name: String?,
    val idempotencyKey: String,
    val maxRetries: Int?,
    val inputData: String?,
)

@Suppress("UNCHECKED_CAST")
internal fun <I> CreateJobRequest<I>.toDto(
    groupId: String,
    mapper: ObjectMapper,
): CreateJobRequestDto {
    val bytes: ByteArray? =
        when (val s = source) {
            is InputSource.None -> null
            is InputSource.Bytes -> s.data
            is InputSource.TypedClass<*> ->
                JacksonPayloadCodec(mapper, s.type as Class<I>).encode(input)
            is InputSource.TypedToken<*> ->
                JacksonPayloadCodec(mapper, (s as InputSource.TypedToken<I>).type).encode(input)
            is InputSource.Coded<*> -> (s.codec as PayloadCodec<I>).encode(input)
        }
    return CreateJobRequestDto(
        groupId = groupId,
        type = type,
        name = name,
        idempotencyKey = idempotencyKey,
        maxRetries = maxRetries,
        inputData = bytes?.let { Base64.getEncoder().encodeToString(it) },
    )
}

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class AcquireJobsRequestDto(
    val groupId: String,
    val type: String,
    val workerId: String,
    val limit: Int?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CompleteJobRequestDto(val workerId: String, val outputData: String?)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ReleaseJobRequestDto(val workerId: String, val availableAt: Long? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class ReportExecutionErrorRequestDto(val workerId: String, val error: String?)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class SaveCheckpointRequestDto(
    val workerId: String,
    val name: String?,
    val previousCheckpointId: String?,
    val data: String?,
)

internal data class JobResponseDto(
    val id: String,
    val groupId: String,
    val name: String,
    val type: String,
    val status: String,
    val retries: Int,
    val maxRetries: Int?,
    val createdAt: String,
    val updatedAt: String,
    val lastAcquiredAt: String?,
    val acquiredByWorkerId: String?,
    val inputData: String?,
    val outputData: String?,
    val version: Long,
)

internal fun JobResponseDto.toDomain(): Job =
    Job(
        id = id,
        groupId = groupId,
        name = name,
        type = type,
        status = parseEnum<JobStatus>(status),
        retries = retries,
        maxRetries = maxRetries,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        lastAcquiredAt = lastAcquiredAt?.let(Instant::parse),
        acquiredByWorkerId = acquiredByWorkerId,
        inputData = inputData?.let(Base64.getDecoder()::decode),
        outputData = outputData?.let(Base64.getDecoder()::decode),
        version = version,
    )

internal data class CheckpointResponseDto(
    val id: String,
    val jobId: String,
    val previousCheckpointId: String?,
    val name: String?,
    val createdAt: String,
    val data: String?,
)

internal fun CheckpointResponseDto.toDomain(): Checkpoint =
    Checkpoint(
        id = id,
        jobId = jobId,
        previousCheckpointId = previousCheckpointId,
        name = name,
        createdAt = Instant.parse(createdAt),
        data = data?.let(Base64.getDecoder()::decode),
    )

internal data class CheckpointPageResponseDto(
    val checkpoints: List<CheckpointResponseDto>,
    val hasMore: Boolean,
)

internal fun CheckpointPageResponseDto.toDomain(): CheckpointPage =
    CheckpointPage(checkpoints = checkpoints.map { it.toDomain() }, hasMore = hasMore)

internal data class JobEventResponseDto(
    val id: String,
    val jobId: String,
    val eventType: String,
    val actorType: String,
    val actorId: String?,
    val createdAt: String,
    val eventMessage: String?,
    val eventDetail: String?,
)

internal fun JobEventResponseDto.toDomain(): JobEvent =
    JobEvent(
        id = id,
        jobId = jobId,
        eventType = parseEnum<JobEventType>(eventType),
        actorType = parseEnum<ActorType>(actorType),
        actorId = actorId,
        createdAt = Instant.parse(createdAt),
        eventMessage = eventMessage,
        eventDetail = eventDetail,
    )

private inline fun <reified E : Enum<E>> parseEnum(value: String): E =
    try {
        enumValueOf<E>(value)
    } catch (_: IllegalArgumentException) {
        throw org.zeplinko.logplay.client.exception.LogPlayServerException(
            500,
            "Unknown ${E::class.simpleName} value from server: $value",
        )
    }
