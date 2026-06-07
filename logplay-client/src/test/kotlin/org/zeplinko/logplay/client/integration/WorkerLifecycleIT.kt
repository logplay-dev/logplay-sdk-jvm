package org.zeplinko.logplay.client.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.run

class WorkerLifecycleIT : AbstractIntegrationTest() {

    /**
     * Travel-booking workflow registered by a `v1` worker. The handler isn't executed in this test
     * — the test only verifies start/stop semantics — but it follows the same multi-step shape as
     * the rest of the suite so the registered API surface is realistic.
     */
    private class TravelBookingV1Handler(private val service: BookingService) :
        JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            return "v1:${flight.bookingId}"
        }
    }

    /**
     * Same shape as [TravelBookingV1Handler] but registered by a `v2` worker after the `v1` worker
     * has stopped — simulates a rolling-deploy where the workerId is reused.
     */
    private class TravelBookingV2Handler(private val service: BookingService) :
        JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            return "v2:${flight.bookingId}"
        }
    }

    @Test
    fun `start then stop transitions isRunning correctly and frees the workerId`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val service = FakeBookingService()

        val w1 = trackedWorker(workerId)
        w1.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TravelBookingV1Handler(service),
        )
        w1.start()
        assertThat(w1.isRunning()).isTrue()

        w1.stop()
        assertThat(w1.isRunning()).isFalse()

        // After stop the workerId is released server-side: a v2 worker with the same id can
        // re-register — the rolling-deploy scenario.
        val w2 = trackedWorker(workerId)
        w2.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TravelBookingV2Handler(service),
        )
        w2.start()
        assertThat(w2.isRunning()).isTrue()
    }
}
