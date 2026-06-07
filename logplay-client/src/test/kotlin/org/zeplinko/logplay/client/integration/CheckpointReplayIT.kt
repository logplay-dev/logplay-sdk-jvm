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

class CheckpointReplayIT : AbstractIntegrationTest() {

    /**
     * Idempotent payment charging — the textbook example for durable execution.
     *
     * The handler validates the passport, books a flight, charges the payment, then throws a
     * simulated post-charge network blip on the first attempt. The retry re-enters the handler, but
     * every prior step (including the charge) replays from its checkpoint instead of re-invoking
     * the service. After the replay, the handler proceeds past the throw and sends the itinerary.
     * Tracking [BookingService.chargePaymentCalls] proves the charge step ran exactly once across
     * the two handler invocations — the "no double-charge" guarantee.
     */
    private class IdempotentChargeHandler(
        private val service: BookingService,
        private val invocationCount: AtomicInteger,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            val attempt = invocationCount.incrementAndGet()
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            val receipt =
                ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            if (attempt == 1) error("network blip after charge")
            val notification =
                ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            return "${receipt.transactionId}/$notification"
        }
    }

    @Test
    fun `ctx_run side effects run exactly once across replays`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")

        val service = FakeBookingService()
        val invocationCount = AtomicInteger(0)

        val req =
            CreateJobRequest.builder(TripBookingRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(request)
                .maxRetries(3)
                .build()
        val job = manager.createJob(req)

        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            IdempotentChargeHandler(service, invocationCount),
        )
        worker.start()

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(60),
            description = "job to reach COMPLETED state",
        ) {
            val events = manager.getEvents(job.id).map { it.eventType }
            JobEventType.COMPLETED in events
        }

        // Every checkpointed service call must run exactly once even though the handler is invoked
        // twice (the first attempt throws after charge; the second replays through every prior
        // checkpoint and finishes the workflow).
        assertThat(service.validatePassportCalls.get()).isEqualTo(1)
        assertThat(service.bookFlightCalls.get()).isEqualTo(1)
        assertThat(service.chargePaymentCalls.get())
            .withFailMessage(
                "chargePaymentCalls expected 1, got ${service.chargePaymentCalls.get()} (handler invoked ${invocationCount.get()} times)"
            )
            .isEqualTo(1)
        // sendItinerary only runs on the second attempt (the first throws before reaching it).
        assertThat(service.sendItineraryCalls.get()).isEqualTo(1)
        assertThat(invocationCount.get()).isGreaterThanOrEqualTo(2)

        val checkpoints = manager.getCheckpoints(job.id)
        assertThat(checkpoints.checkpoints.map { it.name })
            .containsExactly("validate-passport", "book-flight", "charge-payment", "send-itinerary")

        // Persisted charge data must be the JSON-encoded PaymentReceipt — non-null and decodable
        // via the SDK's own codec.
        val charge = checkpoints.checkpoints.first { it.name == "charge-payment" }
        assertThat(charge.data).isNotNull
        val decoded = manager.jsonCodec(PaymentReceipt::class.java).decode(charge.data)
        assertThat(decoded).isEqualTo(PaymentReceipt("txn-rider-42", 1500, "USD"))
    }
}
