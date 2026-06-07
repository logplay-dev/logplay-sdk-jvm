package org.zeplinko.logplay.client.integration

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.run

class ConcurrentWorkersIT : AbstractIntegrationTest() {

    /**
     * Travel-booking dispatcher — a single dispatcher node accepting ride requests from a shared
     * queue. Two such nodes can run in parallel without overlapping work because the server's
     * acquire endpoint hands each job to exactly one worker.
     *
     * The handler runs a four-step booking workflow per request and records the `(jobId →
     * workerId)` mapping in [handledBy] so the test can verify no double-assignment.
     */
    private class DispatcherHandler(
        private val service: BookingService,
        private val workerId: String,
        private val handledBy: ConcurrentHashMap<String, String>,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            handledBy[ctx.jobId()] = workerId
            return "flight:${flight.bookingId}@$workerId"
        }
    }

    @Test
    fun `two workers split jobs without double-take`() {
        val type = uniqueType()
        val n = 10

        val jobIds =
            (1..n).map { i ->
                manager
                    .createJob(
                        CreateJobRequest.builder(TripBookingRequest::class.java)
                            .type(type)
                            .idempotencyKey(uniqueKey("k$i"))
                            .input(TripBookingRequest("rider-$i", "NYC"))
                            .build()
                    )
                    .id
            }

        val handledBy = ConcurrentHashMap<String, String>()
        val w1Id = uniqueWorkerId("w1")
        val w2Id = uniqueWorkerId("w2")
        val w1 = trackedWorker(w1Id)
        val w2 = trackedWorker(w2Id)
        val service = FakeBookingService()
        w1.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            DispatcherHandler(service, w1Id, handledBy),
        )
        w2.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            DispatcherHandler(service, w2Id, handledBy),
        )
        w1.start()
        w2.start()

        for (jobId in jobIds) {
            Awaits.awaitTrue(
                timeout = Duration.ofSeconds(90),
                description = "job $jobId to complete",
            ) {
                val events = manager.getEvents(jobId).map { it.eventType }
                JobEventType.COMPLETED in events
            }
        }

        // Every job was handled exactly once and only by one of the two workers.
        assertThat(handledBy.keys).containsExactlyInAnyOrderElementsOf(jobIds)
        assertThat(handledBy.values.toSet()).isSubsetOf(setOf(w1Id, w2Id))
    }
}
