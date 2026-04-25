package org.zeplinko.logplay.client.integration

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.registerHandler
import org.zeplinko.logplay.client.run

/**
 * Dedicated codec-diversity coverage. This IT keeps its own [TripRequest] / [TripConfirmation]
 * types because the *codec* is what's under test — the input/output JSON encoding paths must work
 * for arbitrary user types, not just the shared booking fixtures. Each handler also runs a
 * multi-step booking workflow via [BookingService] so the intermediate checkpoint codecs get
 * exercised on every test.
 */
class JsonPayloadCodecIT : AbstractIntegrationTest() {

    /** A trip request submitted by a rider — pickup, ordered list of stops, and headline fare. */
    data class TripRequest(val tripId: String, val stops: List<String>, val fareCents: Int)

    /** Confirmation issued after a trip request is accepted — denormalized for fast lookup. */
    data class TripConfirmation(val tripId: String, val totalStops: Int, val fareCents: Int)

    /**
     * Validates the trip's stop count then runs the booking workflow, returning a typed
     * [TripConfirmation]. Every step is checkpointed so a partial failure later in the chain does
     * not redo the validation or any prior booking call.
     */
    private class TripConfirmationHandler(
        private val service: BookingService,
        private val observed: CompletableFuture<Pair<TripRequest, TripConfirmation>>,
    ) : JobHandler<TripRequest, TripConfirmation> {
        override fun execute(input: TripRequest, ctx: JobContext): TripConfirmation {
            val stopCount = ctx.run<Int>("validate-stops") { input.stops.size }
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.tripId)
            }
            ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.tripId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.tripId) }
            val confirmation = TripConfirmation(input.tripId, stopCount, input.fareCents)
            observed.complete(input to confirmation)
            return confirmation
        }
    }

    /**
     * Looks up a trip by its id; returns null when the request itself is null (representing "no
     * trip context provided"). Declared with the type parameter spelled as nullable on both the
     * override's input and return — proves the [JobHandler] type allows authors to opt into a
     * null-tolerant contract per-handler even though the Java signature now uses platform types.
     */
    private class OptionalTripConfirmationHandler(
        private val service: BookingService,
        private val observed: CompletableFuture<TripConfirmation?>,
    ) : JobHandler<TripRequest?, TripConfirmation?> {
        override fun execute(input: TripRequest?, ctx: JobContext): TripConfirmation? {
            val tripId = ctx.run<String?>("lookup-trip") { input?.tripId }
            ctx.run<PassportValidation?>("validate-passport") {
                tripId?.let { service.validatePassport(it) }
            }
            ctx.run<FlightBooking?>("book-flight") { tripId?.let { service.bookFlight(it) } }
            ctx.run<PaymentReceipt?>("charge-payment") { tripId?.let { service.chargePayment(it) } }
            val out = tripId?.let { TripConfirmation(it, 0, 0) }
            observed.complete(out)
            return out
        }
    }

    /**
     * Multi-step durable workflow that intentionally produces a null promo result and a null
     * handler output. Both `null` and non-null `TripConfirmation`s round-trip through the JSON
     * codec, alongside the booking-fixture data types — proves the null wire path is honored at
     * every codec.
     */
    private class PromoLookupTripHandler(
        private val service: BookingService,
        private val started: CompletableFuture<Unit>,
    ) : JobHandler<TripRequest, TripConfirmation?> {
        override fun execute(input: TripRequest, ctx: JobContext): TripConfirmation? {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.tripId)
            }
            ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.tripId) }
            val promo = ctx.run<TripConfirmation?>("apply-promo-code") { null }
            val fare =
                ctx.run<TripConfirmation>("compute-fare") {
                    TripConfirmation(input.tripId, input.stops.size, input.fareCents)
                }
            started.complete(Unit)
            // Internal invariants — the test asserts the same things via the post-run checkpoint
            // inspection, but pinning them here surfaces a clearer failure if replay diverges.
            check(promo == null) { "promo step should be null on first invocation; got $promo" }
            check(fare == TripConfirmation("trip-99", 1, 100)) {
                "compute-fare mismatch: got $fare"
            }
            // Return null output to also exercise the null wire outputData path.
            return null
        }
    }

    /**
     * Looks up a rider's most recent trip — input is a String riderId (UTF8-encoded), output is a
     * JSON-encoded [TripConfirmation]. Demonstrates mixing codec sources per handler: one codec for
     * input, a different codec for output, plus intermediate JSON-encoded booking-fixture
     * checkpoints in between.
     */
    private class LookupTripByRiderHandler(
        private val service: BookingService,
        private val observed: CompletableFuture<TripConfirmation>,
    ) : JobHandler<String, TripConfirmation> {
        override fun execute(input: String, ctx: JobContext): TripConfirmation {
            val tripId = ctx.run<String>("find-active-trip") { "trip-via-$input" }
            ctx.run<PassportValidation>("validate-passport") { service.validatePassport(input) }
            ctx.run<FlightBooking>("book-flight") { service.bookFlight(input) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input) }
            val confirmation = TripConfirmation(tripId, 0, 0)
            observed.complete(confirmation)
            return confirmation
        }
    }

    @Test
    fun `JSON-encoded input and output round-trip via Class-based ergonomic API`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val trip =
            TripRequest(tripId = "trip-42", stops = listOf("JFK", "LGA", "EWR"), fareCents = 1500)

        val req =
            CreateJobRequest.builder(TripRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(trip)
                .build()
        val job = manager.createJob(req)

        val observed = CompletableFuture<Pair<TripRequest, TripConfirmation>>()
        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripRequest::class.java,
            TripConfirmation::class.java,
            TripConfirmationHandler(FakeBookingService(), observed),
        )
        worker.start()

        val (decodedInput, returnedOutput) = observed.get(30, TimeUnit.SECONDS)
        assertThat(decodedInput).isEqualTo(trip)
        assertThat(returnedOutput).isEqualTo(TripConfirmation("trip-42", 3, 1500))

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(30),
            description = "job to record COMPLETED event",
        ) {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }
    }

    @Test
    fun `typed handler receives null input when request omits input value`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()

        // Typed builder with NO .input(...) — wire inputData is null.
        val req =
            CreateJobRequest.builder(TripRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .build()
        val job = manager.createJob(req)

        val observed = CompletableFuture<TripConfirmation?>()
        val worker = trackedWorker(workerId)
        worker.registerHandler<TripRequest?, TripConfirmation?>(
            type,
            OptionalTripConfirmationHandler(FakeBookingService(), observed),
        )
        worker.start()

        val returned = observed.get(30, TimeUnit.SECONDS)
        assertThat(returned).isNull()

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(30),
            description = "job to record COMPLETED event",
        ) {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }
    }

    @Test
    fun `handler returning null output produces a checkpoint chain with null data`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val trip = TripRequest(tripId = "trip-99", stops = listOf("only-one"), fareCents = 100)

        val req =
            CreateJobRequest.builder(TripRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(trip)
                .build()
        val job = manager.createJob(req)

        val started = CompletableFuture<Unit>()
        val worker = trackedWorker(workerId)
        worker.registerHandler<TripRequest, TripConfirmation?>(
            type,
            PromoLookupTripHandler(FakeBookingService(), started),
        )
        worker.start()
        started.get(30, TimeUnit.SECONDS)

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(30),
            description = "job to record COMPLETED event",
        ) {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }

        val checkpoints = manager.getCheckpoints(job.id).checkpoints
        assertThat(checkpoints.map { it.name })
            .containsExactly("validate-passport", "book-flight", "apply-promo-code", "compute-fare")
        // Promo lookup: null result -> null data on the wire.
        val promo = checkpoints.first { it.name == "apply-promo-code" }
        assertThat(promo.data).isNull()
        // Fare computation: real TripConfirmation -> non-null bytes.
        val fare = checkpoints.first { it.name == "compute-fare" }
        assertThat(fare.data).isNotNull
        assertThat(fare.data!!.size).isGreaterThan(0)
    }

    @Test
    fun `mix-and-match codec sources via manager jsonCodec`() {
        // Input via custom codec (UTF8 string riderId); output via JSON of a typed bean — proves
        // the codec/Class/TypeToken paths can be combined per-handler.
        val type = uniqueType()
        val workerId = uniqueWorkerId()

        val req =
            CreateJobRequest.builder(org.zeplinko.logplay.client.codec.Codecs.UTF8)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input("rider-mix")
                .build()
        val job = manager.createJob(req)

        val observed = CompletableFuture<TripConfirmation>()
        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            org.zeplinko.logplay.client.codec.Codecs.UTF8,
            manager.jsonCodec(TripConfirmation::class.java),
            LookupTripByRiderHandler(FakeBookingService(), observed),
        )
        worker.start()

        val returned = observed.get(30, TimeUnit.SECONDS)
        assertThat(returned).isEqualTo(TripConfirmation("trip-via-rider-mix", 0, 0))

        Awaits.awaitTrue(timeout = Duration.ofSeconds(30), description = "job to complete") {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }
    }
}
