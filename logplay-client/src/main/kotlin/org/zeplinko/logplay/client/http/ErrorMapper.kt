package org.zeplinko.logplay.client.http

import com.fasterxml.jackson.databind.ObjectMapper
import org.zeplinko.logplay.client.exception.BlankGroupIdException
import org.zeplinko.logplay.client.exception.BlankIdempotencyKeyException
import org.zeplinko.logplay.client.exception.BlankJobIdException
import org.zeplinko.logplay.client.exception.BlankJobTypeException
import org.zeplinko.logplay.client.exception.BlankWorkerIdException
import org.zeplinko.logplay.client.exception.CheckpointNotFoundException
import org.zeplinko.logplay.client.exception.ConflictException
import org.zeplinko.logplay.client.exception.DuplicateIdempotencyKeyException
import org.zeplinko.logplay.client.exception.ForbiddenException
import org.zeplinko.logplay.client.exception.InvalidCheckpointDataException
import org.zeplinko.logplay.client.exception.InvalidCheckpointNameException
import org.zeplinko.logplay.client.exception.InvalidCheckpointOrderException
import org.zeplinko.logplay.client.exception.InvalidGroupIdException
import org.zeplinko.logplay.client.exception.InvalidIdempotencyKeyException
import org.zeplinko.logplay.client.exception.InvalidJobInputDataException
import org.zeplinko.logplay.client.exception.InvalidJobNameException
import org.zeplinko.logplay.client.exception.InvalidJobOutputDataException
import org.zeplinko.logplay.client.exception.InvalidJobTypeException
import org.zeplinko.logplay.client.exception.InvalidLimitException
import org.zeplinko.logplay.client.exception.InvalidMaxRetriesException
import org.zeplinko.logplay.client.exception.InvalidQueryParameterException
import org.zeplinko.logplay.client.exception.InvalidRequestBodyException
import org.zeplinko.logplay.client.exception.InvalidWorkerIdException
import org.zeplinko.logplay.client.exception.InvalidWorkerTimeoutException
import org.zeplinko.logplay.client.exception.JobConcurrentModificationException
import org.zeplinko.logplay.client.exception.JobNotAbortableException
import org.zeplinko.logplay.client.exception.JobNotAcquiredException
import org.zeplinko.logplay.client.exception.JobNotFoundException
import org.zeplinko.logplay.client.exception.JobNotOwnedByWorkerException
import org.zeplinko.logplay.client.exception.LogPlayClientException
import org.zeplinko.logplay.client.exception.LogPlayServerException
import org.zeplinko.logplay.client.exception.LogPlayValidationException
import org.zeplinko.logplay.client.exception.NotFoundException
import org.zeplinko.logplay.client.exception.WorkerAlreadyRegisteredException
import org.zeplinko.logplay.client.exception.WorkerCondemnedException
import org.zeplinko.logplay.client.exception.WorkerNotFoundException

/**
 * Maps non-2xx HTTP responses to typed SDK exceptions. The server returns errors as `{"error":
 * "..."}`; we match on the message text to pick a specific subtype, falling back to the category
 * parent and finally [LogPlayServerException].
 */
internal class ErrorMapper(private val mapper: ObjectMapper) {

    fun translate(status: Int, body: ByteArray): LogPlayClientException {
        val message = parseMessage(body)
        return when (status) {
            400 -> mapValidation(message)
            403 -> mapForbidden(message)
            404 -> mapNotFound(message)
            409 -> mapConflict(message)
            else -> LogPlayServerException(status, message)
        }
    }

    private fun parseMessage(body: ByteArray): String {
        if (body.isEmpty()) return ""
        return try {
            val node = mapper.readTree(body)
            node.get("error")?.asText() ?: String(body, Charsets.UTF_8)
        } catch (_: Exception) {
            String(body, Charsets.UTF_8)
        }
    }

    private fun mapValidation(message: String): LogPlayValidationException {
        val msg = message.lowercase()
        return when {
            "groupid" in msg && "blank" in msg -> BlankGroupIdException(message)
            "groupid" in msg -> InvalidGroupIdException(message)
            "type" in msg && "blank" in msg -> BlankJobTypeException(message)
            "jobtype" in msg || "job type" in msg -> InvalidJobTypeException(message)
            "jobid" in msg && "blank" in msg -> BlankJobIdException(message)
            "workerid" in msg && "blank" in msg -> BlankWorkerIdException(message)
            "workerid" in msg -> InvalidWorkerIdException(message)
            "idempotencykey" in msg && "blank" in msg -> BlankIdempotencyKeyException(message)
            "idempotency" in msg -> InvalidIdempotencyKeyException(message)
            "checkpoint" in msg && "name" in msg -> InvalidCheckpointNameException(message)
            "checkpoint" in msg && ("data" in msg || "base64" in msg) ->
                InvalidCheckpointDataException(message)
            "input" in msg -> InvalidJobInputDataException(message)
            "output" in msg -> InvalidJobOutputDataException(message)
            "maxretries" in msg || "max retries" in msg -> InvalidMaxRetriesException(message)
            "limit" in msg -> InvalidLimitException(message)
            "timeout" in msg -> InvalidWorkerTimeoutException(message)
            "name" in msg && "job" in msg -> InvalidJobNameException(message)
            "query" in msg -> InvalidQueryParameterException(message)
            "body" in msg || "json" in msg -> InvalidRequestBodyException(message)
            else -> LogPlayValidationException(message)
        }
    }

    private fun mapForbidden(message: String): ForbiddenException {
        return if ("condemn" in message.lowercase()) {
            val workerId = extractId(message) ?: ""
            WorkerCondemnedException(workerId)
        } else ForbiddenException(message)
    }

    private fun mapNotFound(message: String): NotFoundException {
        val msg = message.lowercase()
        val id = extractId(message) ?: ""
        return when {
            "checkpoint" in msg -> CheckpointNotFoundException(id)
            "worker" in msg -> WorkerNotFoundException(id)
            "job" in msg -> JobNotFoundException(id)
            else -> NotFoundException(message)
        }
    }

    private fun mapConflict(message: String): ConflictException {
        val msg = message.lowercase()
        return when {
            "idempotency" in msg && ("duplicate" in msg || "already exists" in msg) ->
                DuplicateIdempotencyKeyException("", extractId(message) ?: "")
            "concurrent" in msg -> JobConcurrentModificationException(extractId(message) ?: "")
            "checkpoint" in msg && ("order" in msg || "previous" in msg || "chain" in msg) ->
                InvalidCheckpointOrderException(extractId(message) ?: "", message)
            "abortable" in msg || "cannot be aborted" in msg ->
                JobNotAbortableException(extractId(message) ?: "", message)
            "acquired" in msg && "not" in msg ->
                JobNotAcquiredException(extractId(message) ?: "", message)
            "owned" in msg -> JobNotOwnedByWorkerException(extractId(message) ?: "", "", message)
            "worker" in msg && ("registered" in msg || "already exists" in msg) ->
                WorkerAlreadyRegisteredException(extractId(message) ?: "")
            else -> ConflictException(message)
        }
    }

    private fun extractId(message: String): String? {
        val match = ID_REGEX.find(message) ?: return null
        return match.groupValues[1]
    }

    private companion object {
        private val ID_REGEX = Regex("[:'\\s]([A-Za-z0-9_\\-]{8,})['\\s]?")
    }
}
