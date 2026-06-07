package org.zeplinko.logplay.client

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.net.http.HttpClient
import java.time.Duration
import org.zeplinko.logplay.client.codec.JacksonPayloadCodec
import org.zeplinko.logplay.client.codec.PayloadCodec
import org.zeplinko.logplay.client.codec.TypeToken
import org.zeplinko.logplay.client.http.ErrorMapper
import org.zeplinko.logplay.client.http.JdkHttpTransport
import org.zeplinko.logplay.client.job.JobClient
import org.zeplinko.logplay.client.model.CheckpointPage
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.Job
import org.zeplinko.logplay.client.model.JobEvent
import org.zeplinko.logplay.client.retry.ExponentialBackoffRetryPolicy
import org.zeplinko.logplay.client.retry.RetryPolicy
import org.zeplinko.logplay.client.worker.Worker
import org.zeplinko.logplay.client.worker.WorkerConfig

/**
 * Entry point for the LogPlay JVM client SDK.
 *
 * Construct via [LogPlayManager.builder] (`baseUrl` and `groupId` required). A single manager is
 * intended to be process-wide — it owns shared infrastructure (HTTP client, JSON mapper, retry
 * policy) reused across every job submission and every [Worker] created through [newWorker].
 *
 * `groupId` is committed at the manager level (Kafka-consumer-group style): both the producer side
 * ([createJob]) and the consumer side (workers) inherit it. For the rare case where one service
 * submits jobs into a different group than its own workers consume, use the [createJob] overload
 * that takes a `groupIdOverride`.
 *
 * Resources held by the manager:
 * - A shared `java.net.http.HttpClient` (HTTP/2). JDK 11 has no `close()`, so the SDK doesn't
 *   pretend to.
 * - A shared [ObjectMapper] (force-registers `KotlinModule` and `JavaTimeModule` even if the caller
 *   supplies their own). This mapper backs [JacksonPayloadCodec] instances minted by [jsonCodec],
 *   the `Class<T>`/`TypeToken<T>` overloads on [Worker.registerHandler], and the typed
 *   [CreateJobRequest] factories.
 * - A [defaultRetryPolicy] used by the worker runtime for retryable transport-layer failures.
 *
 * All manager methods are blocking; the SDK holds no callback thread pool. Wrap calls in your own
 * executor or coroutine context if you need asynchrony.
 */
public class LogPlayManager
internal constructor(
    private val transport: JdkHttpTransport,
    private val mapper: ObjectMapper,
    public val defaultRetryPolicy: RetryPolicy,
    private val baseUrl: String,
    private val groupId: String,
) {
    private val jobClient = JobClient(baseUrl, transport, mapper)

    /** The `groupId` this manager was built with (Kafka-consumer-group identity). */
    public fun groupId(): String = groupId

    // Job CRUD ---------------------------------------------------------------------------------

    /**
     * Submit a job for execution under this manager's [groupId]. Encoding of `request.input` is
     * performed by the codec/type committed at the [CreateJobRequest] factory.
     *
     * Blocking call — wrap it in your own executor or coroutine context for non-blocking
     * submission.
     */
    public fun <I> createJob(request: CreateJobRequest<I>): Job =
        jobClient.createJob(groupId, request)

    /**
     * Submit a job into [groupIdOverride] instead of this manager's default group. Use only when a
     * service legitimately produces jobs for a downstream group different from the one its own
     * workers consume.
     */
    public fun <I> createJob(request: CreateJobRequest<I>, groupIdOverride: String): Job {
        require(groupIdOverride.isNotBlank()) { "groupIdOverride must not be blank" }
        return jobClient.createJob(groupIdOverride, request)
    }

    // Codec helpers -----------------------------------------------------------------------------

    /**
     * Construct a JSON [PayloadCodec] for [type] backed by this manager's [ObjectMapper].
     *
     * Useful for mix-and-match cases on [Worker.registerHandler] (e.g., JSON input + custom binary
     * output). Most users won't need this directly — the Class/TypeToken-form overloads on
     * `registerHandler` and `CreateJobRequest.builder(...)` already use this internally.
     */
    public fun <T> jsonCodec(type: Class<T>): PayloadCodec<T> = JacksonPayloadCodec.of(mapper, type)

    /** As [jsonCodec], but for parameterized generic types (e.g., `List<Order>`). */
    public fun <T> jsonCodec(type: TypeToken<T>): PayloadCodec<T> =
        JacksonPayloadCodec.of(mapper, type)

    /** Abort a job (server transitions it to `ABORTED`). Server rejects already-finished jobs. */
    public fun abortJob(jobId: String): Job = jobClient.abort(jobId)

    /**
     * Fetch one page of a job's checkpoints in chronological order.
     *
     * @param jobId the job to fetch checkpoints for
     * @param after optional cursor — pass the [Checkpoint.id] of the last item from the previous
     *   page to continue. `null` starts from the beginning.
     * @param limit optional page size; the server caps at 100.
     */
    @JvmOverloads
    public fun getCheckpoints(
        jobId: String,
        after: String? = null,
        limit: Int? = null,
    ): CheckpointPage = jobClient.getCheckpoints(jobId, after, limit)

    /** Fetch the full lifecycle event stream for a job. */
    public fun getEvents(jobId: String): List<JobEvent> = jobClient.getEvents(jobId)

    // Worker factory ----------------------------------------------------------------------------

    /**
     * Build a [Worker] bound to [workerId] within this manager's [groupId]. The returned worker is
     * **not** started — register handlers via `worker.registerHandler(...)` then call
     * `worker.start()`.
     *
     * @param workerId server-unique within the group; must not be blank
     * @param config tunables; defaults are produced by [WorkerConfig.defaults]
     */
    @JvmOverloads
    public fun newWorker(workerId: String, config: WorkerConfig = WorkerConfig.defaults()): Worker {
        require(workerId.isNotBlank()) { "workerId must not be blank" }
        return Worker(groupId, workerId, config, jobClient, mapper, defaultRetryPolicy)
    }

    // Builder -----------------------------------------------------------------------------------

    /**
     * Hand-written builder for [LogPlayManager]. `baseUrl` and `groupId` are required; everything
     * else has a sensible default.
     */
    public class Builder {
        private var baseUrl: String? = null
        private var groupId: String? = null
        private var httpTimeout: Duration = Duration.ofSeconds(30)
        private var objectMapper: ObjectMapper? = null
        private var defaultRetryPolicy: RetryPolicy = ExponentialBackoffRetryPolicy()
        private var headers: Map<String, String> = emptyMap()
        private var userAgent: String = "logplay-client-jvm/0.0.1"

        /** **Required.** Base URL of the LogPlay server, e.g., `http://localhost:8080`. */
        public fun baseUrl(value: String): Builder = apply { this.baseUrl = value.trimEnd('/') }

        /**
         * **Required.** Service-wide consumer-group identity. Both [createJob] (producer) and
         * [Worker] instances built via [newWorker] inherit this value. Must not be blank.
         */
        public fun groupId(value: String): Builder = apply { this.groupId = value }

        /** Per-request HTTP timeout (also used as connect timeout). Default `30s`. */
        public fun httpTimeout(value: Duration): Builder = apply { this.httpTimeout = value }

        /**
         * Custom Jackson [ObjectMapper]. The SDK force-registers `KotlinModule` and
         * `JavaTimeModule` on the supplied mapper (both are idempotent), and sets
         * `NON_NULL`/lenient unknown-properties settings. Pass your own if you have additional
         * modules (e.g., custom serializers) you want available on every codec.
         */
        public fun objectMapper(value: ObjectMapper): Builder = apply { this.objectMapper = value }

        /**
         * Retry policy used by the worker runtime around server calls (`complete`, `release`,
         * `report-error`). Default = [ExponentialBackoffRetryPolicy] with full jitter. Only
         * exceptions implementing [Retryable] are actually retried.
         */
        public fun defaultRetryPolicy(value: RetryPolicy): Builder = apply {
            this.defaultRetryPolicy = value
        }

        /**
         * Default headers added to every request. `User-Agent` is auto-added unless explicitly
         * present in this map; everything else is appended verbatim.
         */
        public fun headers(value: Map<String, String>): Builder = apply {
            this.headers = value.toMap()
        }

        /** Override the auto-added `User-Agent` header. */
        public fun userAgent(value: String): Builder = apply { this.userAgent = value }

        public fun build(): LogPlayManager {
            val baseUrl = requireNotNull(baseUrl) { "baseUrl is required" }
            val groupId = requireNotNull(groupId) { "groupId is required" }
            require(groupId.isNotBlank()) { "groupId must not be blank" }
            val mapper =
                (objectMapper ?: defaultMapper()).apply {
                    registerModule(JavaTimeModule())
                    registerModule(KotlinModule.Builder().build())
                    setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                }
            val errorMapper = ErrorMapper(mapper)
            val httpClient =
                HttpClient.newBuilder()
                    .connectTimeout(httpTimeout)
                    .version(HttpClient.Version.HTTP_2)
                    .build()
            val effectiveHeaders =
                if (headers.containsKey("User-Agent")) headers
                else headers + ("User-Agent" to userAgent)
            val transport =
                JdkHttpTransport(
                    client = httpClient,
                    mapper = mapper,
                    errorMapper = errorMapper,
                    requestTimeout = httpTimeout,
                    defaultHeaders = effectiveHeaders,
                )
            return LogPlayManager(
                transport = transport,
                mapper = mapper,
                defaultRetryPolicy = defaultRetryPolicy,
                baseUrl = baseUrl,
                groupId = groupId,
            )
        }

        private fun defaultMapper(): ObjectMapper = ObjectMapper()
    }

    public companion object {
        /**
         * Java-friendly entry point: `LogPlayManager.builder().baseUrl(...).groupId(...).build()`.
         */
        @JvmStatic public fun builder(): Builder = Builder()
    }
}
