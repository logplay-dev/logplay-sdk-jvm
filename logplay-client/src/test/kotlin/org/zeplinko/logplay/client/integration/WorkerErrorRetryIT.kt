package org.zeplinko.logplay.client.integration

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.run

class WorkerErrorRetryIT : AbstractIntegrationTest() {

    /**
     * Travel-booking handler whose payment step always fails — simulates a real-world shape where
     * upstream validation and reservations succeed but the downstream payment provider declines.
     *
     * Each predecessor step (validate-passport, book-flight, book-hotel) is checkpointed so its
     * result persists across retries — the service is invoked only on the first attempt; subsequent
     * replays return the persisted value without re-calling the service. The charge step then
     * throws on every attempt, exhausting the retry budget and driving the job to FAILED.
     */
    private class FlakyPaymentHandler(
        private val service: BookingService,
        private val invocations: AtomicInteger,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            invocations.incrementAndGet()
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<HotelReservation>("book-hotel") { service.bookHotel(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            return ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
        }
    }

    @Test
    fun `handler whose payment always throws transitions job to FAILED after maxRetries`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")

        val service = FakeBookingService()
        service.failChargePaymentAlways(
            IllegalStateException("Payment provider declined: insufficient funds")
        )
        val invocations = AtomicInteger(0)
        val maxRetries = 2

        val req =
            CreateJobRequest.builder(TripBookingRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(request)
                .maxRetries(maxRetries)
                .build()
        val job = manager.createJob(req)

        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            FlakyPaymentHandler(service, invocations),
        )
        worker.start()

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(60),
            description = "job to reach FAILED state",
        ) {
            val events = manager.getEvents(job.id).map { it.eventType }
            JobEventType.FAILED in events
        }

        val events = manager.getEvents(job.id).map { it.eventType }
        assertThat(events).contains(JobEventType.FAILED)

        // Each handler attempt produces exactly one ERROR_REPORTED event — invariants tighter
        // than the prior >= 1 floor without baking in a specific server-side retry count
        // (maxRetries semantics may differ from "retries after initial" vs "max total attempts").
        val errorReports = events.count { it == JobEventType.ERROR_REPORTED }
        assertThat(errorReports).isEqualTo(invocations.get())
        assertThat(invocations.get()).isGreaterThanOrEqualTo(1)

        // Exactly one FAILED event — terminal state, not retried further.
        assertThat(events.count { it == JobEventType.FAILED }).isEqualTo(1)
        // No COMPLETED — handler never succeeded.
        assertThat(events).doesNotContain(JobEventType.COMPLETED)
        // Each attempt was preceded by an ACQUIRED event.
        assertThat(events.count { it == JobEventType.ACQUIRED }).isEqualTo(invocations.get())

        // Predecessor steps each ran exactly once thanks to checkpoint replay; charge ran on every
        // attempt because it never produced a checkpoint to replay from.
        assertThat(service.validatePassportCalls.get()).isEqualTo(1)
        assertThat(service.bookFlightCalls.get()).isEqualTo(1)
        assertThat(service.bookHotelCalls.get()).isEqualTo(1)
        assertThat(service.chargePaymentCalls.get()).isEqualTo(invocations.get())
        // send-itinerary is never reached because charge always throws.
        assertThat(service.sendItineraryCalls.get()).isEqualTo(0)
    }
}
