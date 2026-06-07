package org.zeplinko.logplay.client.integration

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.run

class EventsAndCheckpointsIT : AbstractIntegrationTest() {

    /**
     * Multi-step travel booking. Each step persists a checkpoint with the service's JSON-encoded
     * response. Realistic itineraries produce many checkpoints, motivating the cursor-based
     * pagination on `getCheckpoints` exercised by the test.
     */
    private class TripBookingHandler(private val service: BookingService) :
        JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<HotelReservation>("book-hotel") { service.bookHotel(input.userId) }
            ctx.run<String>("book-cab") { service.bookCab(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            return "itinerary-confirmed"
        }
    }

    @Test
    fun `getCheckpoints supports cursor-based pagination and getEvents reflects the lifecycle`() {
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

        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            TripBookingHandler(FakeBookingService()),
        )
        worker.start()

        Awaits.awaitTrue(timeout = Duration.ofSeconds(60), description = "job to complete") {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }

        // Pagination across 5 checkpoints with limit=2: page1 (2, hasMore=true), page2 (2,
        // hasMore=true), page3 (1, hasMore=false).
        val page1 = manager.getCheckpoints(job.id, null, 2)
        assertThat(page1.checkpoints).hasSize(2)
        assertThat(page1.hasMore).isTrue()
        assertThat(page1.checkpoints.map { it.name })
            .containsExactly("validate-passport", "book-flight")

        val page2 = manager.getCheckpoints(job.id, page1.checkpoints.last().id, 2)
        assertThat(page2.checkpoints).hasSize(2)
        assertThat(page2.hasMore).isTrue()
        assertThat(page2.checkpoints.map { it.name }).containsExactly("book-hotel", "book-cab")

        val page3 = manager.getCheckpoints(job.id, page2.checkpoints.last().id, 2)
        assertThat(page3.checkpoints).hasSize(1)
        assertThat(page3.hasMore).isFalse()
        assertThat(page3.checkpoints.first().name).isEqualTo("charge-payment")

        // Each checkpoint has a populated identity, jobId match, and chained previousId where
        // applicable.
        val all = page1.checkpoints + page2.checkpoints + page3.checkpoints
        for ((idx, cp) in all.withIndex()) {
            assertThat(cp.id).isNotBlank
            assertThat(cp.jobId).isEqualTo(job.id)
            assertThat(cp.createdAt).isNotNull
            // First checkpoint has no predecessor; later ones chain to the previous id.
            if (idx == 0) {
                assertThat(cp.previousCheckpointId).isNull()
            } else {
                assertThat(cp.previousCheckpointId).isEqualTo(all[idx - 1].id)
            }
            // ctx.run with a typed result -> JSON encoding -> non-null bytes.
            assertThat(cp.data).isNotNull
            assertThat(cp.data!!.size).isGreaterThan(0)
        }

        // Lifecycle events present in order.
        val eventTypes = manager.getEvents(job.id).map { it.eventType }
        assertThat(eventTypes)
            .containsSubsequence(
                JobEventType.CREATED,
                JobEventType.ACQUIRED,
                JobEventType.COMPLETED,
            )
    }
}
