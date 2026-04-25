package org.zeplinko.logplay.client.integration

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.run

class MultiStepDurableIT : AbstractIntegrationTest() {

    /**
     * End-to-end travel booking against a fake [BookingService]: reserve a flight, reserve a hotel,
     * dispatch a cab, charge the rider, and send the itinerary. Each step persists a checkpoint —
     * on replay (e.g., worker crash mid-booking), the steps already completed return their stored
     * result without re-executing. This is the canonical multi-step durable-execution pattern. Most
     * service methods return Jackson-encoded data classes so the JSON codec is exercised on every
     * checkpoint along the chain, and the input itself is a typed [TripBookingRequest] so the input
     * codec path is exercised too.
     */
    private class TripBookingHandler(private val service: BookingService) :
        JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            val hotel = ctx.run<HotelReservation>("book-hotel") { service.bookHotel(input.userId) }
            val cab = ctx.run<String>("book-cab") { service.bookCab(input.userId) }
            val receipt =
                ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            val notification =
                ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            return "${flight.bookingId}/${hotel.reservationId}/$cab/${receipt.transactionId}/$notification"
        }
    }

    @Test
    fun `multi-step booking workflow produces one checkpoint per step in order`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")

        val req =
            CreateJobRequest.builder(TripBookingRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(request)
                .build()
        val job = manager.createJob(req)

        val service = FakeBookingService()
        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TripBookingHandler(service),
        )
        worker.start()

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(60),
            description = "job to reach COMPLETED state",
        ) {
            val events = manager.getEvents(job.id).map { it.eventType }
            JobEventType.COMPLETED in events
        }

        val checkpoints = manager.getCheckpoints(job.id).checkpoints
        assertThat(checkpoints.map { it.name })
            .containsExactly(
                "book-flight",
                "book-hotel",
                "book-cab",
                "charge-payment",
                "send-itinerary",
            )

        // Each service method ran exactly once — no replays since the worker didn't crash.
        assertThat(service.bookFlightCalls.get()).isEqualTo(1)
        assertThat(service.bookHotelCalls.get()).isEqualTo(1)
        assertThat(service.bookCabCalls.get()).isEqualTo(1)
        assertThat(service.chargePaymentCalls.get()).isEqualTo(1)
        assertThat(service.sendItineraryCalls.get()).isEqualTo(1)
    }
}
