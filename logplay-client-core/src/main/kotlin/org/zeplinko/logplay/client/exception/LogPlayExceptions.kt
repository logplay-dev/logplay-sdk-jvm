package org.zeplinko.logplay.client.exception

import org.zeplinko.logplay.client.retry.Retryable

/**
 * Root of the SDK's exception hierarchy. Every typed exception thrown by the SDK extends this —
 * including HTTP failures ([LogPlayServerException]), transport failures
 * ([LogPlayTransportException]), and the validation/not-found/forbidden/conflict categories mapped
 * from the server's HTTP status codes.
 *
 * Catch this if you want a single safety net; catch a category parent
 * ([LogPlayValidationException], [NotFoundException], [ForbiddenException], [ConflictException])
 * for finer-grained handling.
 */
public open class LogPlayClientException
@JvmOverloads
constructor(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Fallback for non-2xx HTTP responses that don't map to a more specific typed exception (e.g.,
 * unknown 5xx, or a 4xx whose error message doesn't match any keyword the SDK recognizes).
 *
 * If you see this in production, the server likely added a new error condition the SDK's
 * `ErrorMapper` doesn't recognize yet — file an issue with the [httpStatus] and message.
 */
public open class LogPlayServerException(public val httpStatus: Int, message: String) :
    LogPlayClientException("HTTP $httpStatus: $message")

/**
 * Wraps any IO/transport-layer failure — network errors, timeouts, malformed responses, connection
 * refused, etc. Implements [Retryable], so the SDK's worker runtime retries these automatically
 * (around `complete`, `release`, `report-error` calls) under the configured
 * [org.zeplinko.logplay.client.retry.RetryPolicy].
 */
public class LogPlayTransportException
@JvmOverloads
constructor(message: String, cause: Throwable? = null) :
    LogPlayClientException(message, cause), Retryable

// --- Category parents ---------------------------------------------------------------------------

/** Parent for all `400 Bad Request` validation failures from the server. Not retryable. */
public open class LogPlayValidationException(message: String) : LogPlayClientException(message)

/** Parent for all `404 Not Found` failures from the server. Not retryable. */
public open class NotFoundException(message: String) : LogPlayClientException(message)

/** Parent for all `403 Forbidden` failures from the server. Not retryable. */
public open class ForbiddenException(message: String) : LogPlayClientException(message)

/**
 * Parent for all `409 Conflict` failures from the server. Most subtypes are not retryable —
 * [JobConcurrentModificationException] is the exception (it implements [Retryable]).
 */
public open class ConflictException(message: String) : LogPlayClientException(message)

// --- Validation (400) ---------------------------------------------------------------------------

/** `groupId` was blank on a server-side validation. Caller bug — fix the input. */
public class BlankGroupIdException(message: String = "groupId must not be blank") :
    LogPlayValidationException(message)

/** `groupId` failed server-side format validation (length, allowed characters, etc.). */
public class InvalidGroupIdException(message: String) : LogPlayValidationException(message)

/** Job `type` was blank on a server-side validation. */
public class BlankJobTypeException(message: String = "type must not be blank") :
    LogPlayValidationException(message)

/** Job `type` failed server-side format validation. */
public class InvalidJobTypeException(message: String) : LogPlayValidationException(message)

/** Job `name` failed server-side format validation. */
public class InvalidJobNameException(message: String) : LogPlayValidationException(message)

/** `jobId` was blank on a server-side validation. */
public class BlankJobIdException(message: String = "jobId must not be blank") :
    LogPlayValidationException(message)

/** `workerId` was blank on a server-side validation. */
public class BlankWorkerIdException(message: String = "workerId must not be blank") :
    LogPlayValidationException(message)

/** `workerId` failed server-side format validation. */
public class InvalidWorkerIdException(message: String) : LogPlayValidationException(message)

/** Checkpoint `name` failed server-side format validation. */
public class InvalidCheckpointNameException(message: String) : LogPlayValidationException(message)

/** Checkpoint `data` failed server-side validation (e.g., not valid Base64). */
public class InvalidCheckpointDataException(message: String) : LogPlayValidationException(message)

/** Job `inputData` failed server-side validation (e.g., not valid Base64). */
public class InvalidJobInputDataException(message: String) : LogPlayValidationException(message)

/** Job `outputData` failed server-side validation (e.g., not valid Base64). */
public class InvalidJobOutputDataException(message: String) : LogPlayValidationException(message)

/** `maxRetries` was non-positive when supplied on a job creation request. */
public class InvalidMaxRetriesException(message: String = "maxRetries must be positive") :
    LogPlayValidationException(message)

/** `idempotencyKey` was blank on a server-side validation. */
public class BlankIdempotencyKeyException(message: String = "idempotencyKey must not be blank") :
    LogPlayValidationException(message)

/** `idempotencyKey` failed server-side format validation (length, characters). */
public class InvalidIdempotencyKeyException(message: String) : LogPlayValidationException(message)

/** A `limit` query parameter was outside the server's accepted range. */
public class InvalidLimitException(message: String) : LogPlayValidationException(message)

/**
 * Worker timeout fields failed server-side validation — e.g., `sessionTimeoutMs` is not greater
 * than `heartbeatTimeoutMs`. Also raised client-side by `WorkerConfig.Builder.build()` for the same
 * invariant before the request is sent.
 */
public class InvalidWorkerTimeoutException(message: String) : LogPlayValidationException(message)

/** The server rejected the request body as malformed (typically bad JSON). */
public class InvalidRequestBodyException(message: String) : LogPlayValidationException(message)

/** A query parameter failed server-side format validation. */
public class InvalidQueryParameterException(message: String) : LogPlayValidationException(message)

// --- Not found (404) ----------------------------------------------------------------------------

/** No job exists with the given [jobId]. Typically raised by `abortJob`, `getCheckpoints`, etc. */
public class JobNotFoundException(public val jobId: String) :
    NotFoundException("Job not found: $jobId")

/** No checkpoint exists with the given [checkpointId]. */
public class CheckpointNotFoundException(public val checkpointId: String) :
    NotFoundException("Checkpoint not found: $checkpointId")

/**
 * The server has no record of [workerId] — it was never registered, was deregistered, or expired
 * past its session. The worker runtime treats this as terminal and triggers an asynchronous
 * shutdown (`Worker.stop` internally) so the worker stops accepting new jobs cleanly.
 */
public class WorkerNotFoundException(public val workerId: String) :
    NotFoundException("Worker not found: $workerId")

// --- Forbidden (403) ----------------------------------------------------------------------------

/**
 * The server has marked [workerId] as condemned — typically because heartbeats stopped arriving
 * within `heartbeatTimeoutMs` and the server reclaimed its jobs. Once condemned, the server refuses
 * operations on that worker. The worker runtime treats this as terminal (same shutdown path as
 * [WorkerNotFoundException]).
 */
public class WorkerCondemnedException(public val workerId: String) :
    ForbiddenException("Worker is condemned: $workerId")

// --- Conflict (409) -----------------------------------------------------------------------------

/**
 * A job already exists with the same ([groupId], [key]) idempotency pair. The server uses this to
 * make `createJob` idempotent — re-submitting the same key returns the original job's identity
 * rather than creating a duplicate. Catch this if you want to fetch the existing job's status
 * instead of creating a new one.
 */
public class DuplicateIdempotencyKeyException(public val groupId: String, public val key: String) :
    ConflictException("Duplicate idempotency key '$key' in group '$groupId'")

/**
 * A worker tried to act on [jobId] that is not (or no longer) in `ACQUIRED` state — e.g.,
 * completing a job that's already been released or aborted. Often a sign that the worker lost its
 * acquire lease and another worker (or the server) moved the job on.
 */
public class JobNotAcquiredException(public val jobId: String, message: String) :
    ConflictException(message)

/**
 * The job is in a state that doesn't allow `abortJob` — typically because it's already terminated
 * (`FINISHED`, `FAILED`, or `ABORTED`).
 */
public class JobNotAbortableException(public val jobId: String, message: String) :
    ConflictException(message)

/**
 * The worker tried to act on a job it doesn't currently own — typically a stale acquire that the
 * server already reassigned (e.g., after a heartbeat lapse).
 */
public class JobNotOwnedByWorkerException(
    public val jobId: String,
    public val workerId: String,
    message: String = "Job $jobId is not owned by worker $workerId",
) : ConflictException(message)

/**
 * Optimistic concurrency conflict — another writer modified the job between this worker reading it
 * and trying to update it. **Retryable**: the SDK's `RetryExecutor` will re-attempt the operation
 * under the configured [org.zeplinko.logplay.client.retry.RetryPolicy].
 */
public class JobConcurrentModificationException(public val jobId: String) :
    ConflictException("Concurrent modification on job $jobId"), Retryable

/**
 * `saveCheckpoint` was called with a `previousCheckpointId` that doesn't match the job's current
 * checkpoint chain head. The SDK's `CheckpointSaver` recovers from this transparently when the
 * "next" checkpoint on the server matches the same name (idempotent re-attempt); a real chain
 * mismatch surfaces this exception (or [CheckpointDivergenceException]).
 */
public class InvalidCheckpointOrderException(public val jobId: String, message: String) :
    ConflictException(message)

/**
 * A worker tried to register with a [workerId] already in use server-side. Usually means a previous
 * instance hasn't deregistered yet — wait for its session to expire or pick a different id.
 */
public class WorkerAlreadyRegisteredException(public val workerId: String) :
    ConflictException("Worker already registered: $workerId")

// --- SDK-specific -------------------------------------------------------------------------------

/**
 * Thrown on replay when the operation kind at a journal position diverges from what was recorded
 * (e.g., a position recorded as a `run` checkpoint is now being requested as a different kind of
 * operation). Currently unthrown from `JobContextImpl` — name mismatches no longer trigger it
 * (names are debug metadata; the codec's decode is the natural type-mismatch signal). The only live
 * throw site today is `CheckpointSaver`'s chain-ahead recovery path: when a server-side checkpoint
 * at the expected position has a different name from the one the SDK was about to write, that's
 * treated as divergence.
 *
 * Reserved for future expansion when the journal grows non-`run` operation types (sleeps,
 * awakeables, RPC calls) and position-N kind mismatch becomes a true determinism violation.
 */
public class CheckpointDivergenceException(
    public val jobId: String,
    public val cursor: Int,
    public val expected: String?,
    public val actual: String?,
) :
    LogPlayClientException(
        "Checkpoint divergence in job $jobId at position $cursor: expected='$expected', actual='$actual'"
    )
