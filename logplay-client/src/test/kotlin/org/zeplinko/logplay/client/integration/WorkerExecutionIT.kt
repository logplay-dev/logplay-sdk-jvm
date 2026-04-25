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

class WorkerExecutionIT : AbstractIntegrationTest() {

    /**
     * Books a four-step trip against a fake [BookingService] and reports the (request, summary)
     * pair so the test can verify the typed input round-tripped through the wire codec and the
     * handler produced the expected output without a server fetch.
     */
    private class TripBookingHandler(
        private val service: BookingService,
        private val observed: CompletableFuture<Pair<TripBookingRequest?, String>>,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            val passport =
                ctx.run<PassportValidation>("validate-passport") {
                    service.validatePassport(input.userId)
                }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            val hotel = ctx.run<HotelReservation>("book-hotel") { service.bookHotel(input.userId) }
            val receipt =
                ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            val summary =
                "${passport.documentNumber}/${flight.bookingId}/${hotel.reservationId}/${receipt.transactionId}"
            observed.complete(input to summary)
            return summary
        }
    }

    @Test
    fun `worker acquires, executes, and completes a job with typed input round-trip`() {
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

        val observed = CompletableFuture<Pair<TripBookingRequest?, String>>()
        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TripBookingHandler(FakeBookingService(), observed),
        )
        worker.start()

        val (decodedInput, summary) = observed.get(30, TimeUnit.SECONDS)
        assertThat(decodedInput).isEqualTo(request)
        assertThat(summary).isEqualTo("doc-rider-42/flight-rider-42/hotel-rider-42/txn-rider-42")

        Awaits.awaitTrue(
            timeout = Duration.ofSeconds(30),
            description = "job to record COMPLETED event",
        ) {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }
        val eventTypes = manager.getEvents(job.id).map { it.eventType }
        assertThat(eventTypes).contains(JobEventType.ACQUIRED, JobEventType.COMPLETED)
    }
}
