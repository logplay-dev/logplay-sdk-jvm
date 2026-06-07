package org.zeplinko.logplay.client.dto

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import org.zeplinko.logplay.client.model.WorkerInfo

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class RegisterWorkerRequestDto(
    val workerId: String,
    val heartbeatTimeout: Long,
    val sessionTimeout: Long,
)

internal data class WorkerResponseDto(
    val id: String,
    val heartbeatTimeout: Long,
    val sessionTimeout: Long,
    val lastHeartbeatAt: String,
    val registeredAt: String,
)

internal fun WorkerResponseDto.toDomain(): WorkerInfo =
    WorkerInfo(
        id = id,
        heartbeatTimeoutMs = heartbeatTimeout,
        sessionTimeoutMs = sessionTimeout,
        lastHeartbeatAt = Instant.parse(lastHeartbeatAt),
        registeredAt = Instant.parse(registeredAt),
    )
