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
import org.zeplinko.logplay.client.run
import org.zeplinko.logplay.client.worker.WorkerConfig

/**
 * End-to-end coverage for [JobContext.sleep]. Two paths under test:
 * 1. **Short sleep** — remaining wait `<=` `shortSleepThresholdMs`: the handler thread blocks
 *    in-process and no `RELEASED` event fires.
 * 2. **Long sleep** — remaining wait `>` threshold: the handler throws the SDK's release signal,
 *    the worker releases the job to the server with `availableAt = wakeAt`, and the same worker
 *    re-acquires the job once the deadline passes — the post-sleep work runs without re-doing the
 *    durable steps that came before the sleep.
 *
 * Domain: a travel-booking flow that reserves a flight, validates the rider, waits for a
 * confirmation window, then completes the booking. The wait step is the {@code sleep} call.
 */
class SleepIT : AbstractIntegrationTest() {

    /**
     * Travel booking that waits between two halves of the workflow. The sleep is checkpointed so on
     * replay the pre-sleep steps do not re-invoke the service, the post-sleep steps run for the
     * first time, and the same wake-at deadline is honoured.
     */
    private class TravelBookingWithSleepHandler(
        private val service: BookingService,
        private val sleep: Duration,
        private val finished: CompletableFuture<String>,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            ctx.sleep("await-driver-confirmation", sleep)
            val cab = ctx.run<String>("book-cab") { service.bookCab(input.userId) }
            val receipt =
                ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            val notification =
                ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            val summary = "${flight.bookingId}/$cab/${receipt.transactionId}/$notification"
            finished.complete(summary)
            return summary
        }
    }

    @Test
    fun `short sleep blocks in-thread and does not release the job`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")
        val service = FakeBookingService()
        val finished = CompletableFuture<String>()

        val req =
            CreateJobRequest.builder(TripBookingRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(request)
                .build()
        val job = manager.createJob(req)

        // Threshold defaults to 5_000ms; a 200ms sleep stays well under it → no release.
        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TravelBookingWithSleepHandler(service, Duration.ofMillis(200), finished),
        )
        worker.start()

        val result = finished.get(60, TimeUnit.SECONDS)
        assertThat(result).isEqualTo("flight-rider-42/cab-rider-42/txn-rider-42/notif-rider-42")
        assertThat(service.bookFlightCalls.get()).isEqualTo(1)
        assertThat(service.bookCabCalls.get()).isEqualTo(1)
        assertThat(service.chargePaymentCalls.get()).isEqualTo(1)
        assertThat(service.sendItineraryCalls.get()).isEqualTo(1)

        Awaits.awaitTrue(timeout = Duration.ofSeconds(30), description = "job to complete") {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }

        // No RELEASED event: short sleeps stay in-thread.
        val events = manager.getEvents(job.id).map { it.eventType }
        assertThat(events).doesNotContain(JobEventType.RELEASED)

        // Six checkpoints in chain order: flight, validate, sleep marker, cab, charge, notify.
        val checkpoints = manager.getCheckpoints(job.id).checkpoints
        assertThat(checkpoints.map { it.name })
            .containsExactly(
                "book-flight",
                "validate-passport",
                "await-driver-confirmation",
                "book-cab",
                "charge-payment",
                "send-itinerary",
            )
    }

    @Test
    fun `long sleep releases the job, the deadline is honoured, and post-sleep work is durable`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")
        val service = FakeBookingService()
        val finished = CompletableFuture<String>()

        val req =
            CreateJobRequest.builder(TripBookingRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(request)
                .build()
        val job = manager.createJob(req)

        // Lower the threshold so a 2s sleep crosses into "release" territory. Keep the timeouts
        // big enough that a single worker can re-acquire after the deadline.
        val config =
            WorkerConfig.builder()
                .shortSleepThresholdMs(500)
                .acquireInitialBackoffMs(100)
                .acquireMaxBackoffMs(500)
                .build()
        val worker = trackedWorker(workerId, config)
        val sleepDuration = Duration.ofSeconds(2)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TravelBookingWithSleepHandler(service, sleepDuration, finished),
        )

        val startedAt = System.currentTimeMillis()
        worker.start()

        // Wait for the RELEASED event — confirms the worker actually released rather than
        // blocking in-thread.
        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(30),
            description = "RELEASED event after long sleep",
        ) {
            manager.getEvents(job.id).any { it.eventType == JobEventType.RELEASED }
        }
        val released = manager.getEvents(job.id).first { it.eventType == JobEventType.RELEASED }

        // RELEASED must carry an availableAt hint that's at least the sleep duration in the
        // future relative to job start.
        assertThat(released.eventDetail)
            .withFailMessage(
                "RELEASED eventDetail should carry availableAt JSON, got: %s",
                released.eventDetail,
            )
            .isNotNull
        val availableAt = parseAvailableAt(released.eventDetail!!)
        assertThat(availableAt).isGreaterThanOrEqualTo(startedAt + sleepDuration.toMillis())

        // The handler eventually completes — the same worker re-acquires after the deadline,
        // replays through the pre-sleep checkpoints without re-invoking the service, and runs the
        // post-sleep steps.
        val result = finished.get(60, TimeUnit.SECONDS)
        assertThat(result).isEqualTo("flight-rider-42/cab-rider-42/txn-rider-42/notif-rider-42")

        Awaits.awaitTrue(timeout = Duration.ofSeconds(30), description = "job to COMPLETE") {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }

        // Total wall-clock from start to completion is at least the sleep duration: proves the
        // server's `availableAt` gate actually held the job back for the full window.
        val completed = manager.getEvents(job.id).first { it.eventType == JobEventType.COMPLETED }
        val elapsedMs = completed.createdAt.toEpochMilli() - startedAt
        assertThat(elapsedMs).isGreaterThanOrEqualTo(sleepDuration.toMillis())

        // Durable execution: pre-sleep service calls (flight, passport) ran on the first acquire;
        // post-sleep calls (cab, charge, notify) ran on the second. Each ran exactly once even
        // though the handler body executed twice (once per acquire).
        assertThat(service.bookFlightCalls.get()).isEqualTo(1)
        assertThat(service.validatePassportCalls.get()).isEqualTo(1)
        assertThat(service.bookCabCalls.get()).isEqualTo(1)
        assertThat(service.chargePaymentCalls.get()).isEqualTo(1)
        assertThat(service.sendItineraryCalls.get()).isEqualTo(1)

        // Lifecycle: CREATED, ACQUIRED, RELEASED, ACQUIRED again, COMPLETED.
        val eventTypes = manager.getEvents(job.id).map { it.eventType }
        assertThat(eventTypes)
            .containsSubsequence(
                JobEventType.CREATED,
                JobEventType.ACQUIRED,
                JobEventType.RELEASED,
                JobEventType.ACQUIRED,
                JobEventType.COMPLETED,
            )

        // Checkpoint chain ends with the full six entries. The sleep checkpoint persists exactly
        // once across replays (replay reads it, doesn't write it).
        val checkpoints = manager.getCheckpoints(job.id).checkpoints
        assertThat(checkpoints.map { it.name })
            .containsExactly(
                "book-flight",
                "validate-passport",
                "await-driver-confirmation",
                "book-cab",
                "charge-payment",
                "send-itinerary",
            )
        // Sleep marker carries an 8-byte big-endian wake-at — same value as RELEASED.availableAt.
        val sleepMarker = checkpoints.first { it.name == "await-driver-confirmation" }
        assertThat(sleepMarker.data).isNotNull
        assertThat(sleepMarker.data!!.size).isEqualTo(8)
    }

    /**
     * Parse `{"availableAt": 1740000000000}` — the server emits this on `RELEASED` for sleep
     * releases. Tolerant of surrounding whitespace and field ordering.
     */
    private fun parseAvailableAt(json: String): Long {
        val match = Regex("\"availableAt\"\\s*:\\s*(\\d+)").find(json)
        requireNotNull(match) { "availableAt not found in RELEASED eventDetail: $json" }
        return match.groupValues[1].toLong()
    }
}
