package org.zeplinko.logplay.client.integration

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.codec.Codecs
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.run

class MultiTypeWorkerIT : AbstractIntegrationTest() {

    /**
     * Flight-booking workflow registered under one type. Takes no input — models a "default
     * itinerary" job kicked off by a cron, where the rider context is implicit. Demonstrates the
     * [Unit]-input + UTF8-output codec path on the same worker that also handles typed-input jobs.
     */
    private class FlightBookingHandler(private val service: BookingService) :
        JobHandler<Unit, String> {
        override fun execute(input: Unit, ctx: JobContext): String {
            val userId = "default-rider"
            ctx.run<PassportValidation>("validate-passport") { service.validatePassport(userId) }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(userId) }
            ctx.run<String>("send-itinerary") { service.sendItinerary(userId) }
            return "flight:${flight.bookingId}"
        }
    }

    /**
     * Cab-booking workflow registered under a second type on the same worker. Takes a typed
     * [TripBookingRequest] so the worker exercises a JSON-input codec alongside the [Unit] handler
     * above — heterogeneous input codecs co-existing on a single worker.
     */
    private class CabBookingHandler(private val service: BookingService) :
        JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            val cab = ctx.run<String>("book-cab") { service.bookCab(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            return "cab:$cab"
        }
    }

    @Test
    fun `single worker with two registered types completes jobs of both`() {
        val typeA = uniqueType("ta")
        val typeB = uniqueType("tb")
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")

        val jobA =
            manager.createJob(
                CreateJobRequest.builder().type(typeA).idempotencyKey(uniqueKey()).build()
            )
        val jobB =
            manager.createJob(
                CreateJobRequest.builder(TripBookingRequest::class.java)
                    .type(typeB)
                    .idempotencyKey(uniqueKey())
                    .input(request)
                    .build()
            )

        val service = FakeBookingService()
        val worker = trackedWorker(workerId)
        worker
            .registerHandler(typeA, Codecs.UNIT, Codecs.UTF8, FlightBookingHandler(service))
            .registerHandler(
                typeB,
                TripBookingRequest::class.java,
                String::class.java,
                CabBookingHandler(service),
            )
        worker.start()

        for (jobId in listOf(jobA.id, jobB.id)) {
            Awaits.awaitTrue(
                timeout = Duration.ofSeconds(60),
                description = "job $jobId to complete",
            ) {
                val events = manager.getEvents(jobId).map { it.eventType }
                JobEventType.COMPLETED in events
            }
        }

        assertThat(manager.getEvents(jobA.id).map { it.eventType }).contains(JobEventType.COMPLETED)
        assertThat(manager.getEvents(jobB.id).map { it.eventType }).contains(JobEventType.COMPLETED)
    }
}
