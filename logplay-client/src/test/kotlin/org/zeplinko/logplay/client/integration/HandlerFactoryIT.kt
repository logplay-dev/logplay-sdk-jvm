package org.zeplinko.logplay.client.integration

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.zeplinko.logplay.client.handler.HandlerFactory
import org.zeplinko.logplay.client.handler.JobContext
import org.zeplinko.logplay.client.handler.JobHandler
import org.zeplinko.logplay.client.model.CreateJobRequest
import org.zeplinko.logplay.client.model.JobEventType
import org.zeplinko.logplay.client.run

/**
 * End-to-end coverage for the [HandlerFactory] registration path: factory.create() must be called
 * exactly once per job execution, and the per-invocation handler instance is honored.
 */
class HandlerFactoryIT : AbstractIntegrationTest() {

    /**
     * Travel-booking handler scoped to a single dispatch session — the [sessionId] would carry a
     * tracing span / per-ride context in production. The framework calls [BookingFactory.create]
     * once per job, producing a fresh [SessionScopedBookingHandler] with its own [sessionId];
     * that's what makes per-invocation state safe (no concurrent sharing of the session across
     * rides). The handler tags each itinerary notification with the session id so the test can
     * verify per-job isolation if needed.
     */
    private class SessionScopedBookingHandler(
        private val service: BookingService,
        private val sessionId: String,
        private val handlerCalls: AtomicInteger,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            handlerCalls.incrementAndGet()
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            val notification =
                ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            return "${flight.bookingId}@$sessionId/$notification"
        }
    }

    /**
     * Factory that materializes a fresh [SessionScopedBookingHandler] per job execution. Each
     * `create()` mints a new sessionId; the framework invokes this once per acquired job, so each
     * ride gets its own handler instance with its own per-ride state.
     */
    private class BookingFactory(
        private val service: BookingService,
        private val factoryCalls: AtomicInteger,
        private val handlerCalls: AtomicInteger,
    ) : HandlerFactory<TripBookingRequest, String> {
        override fun create(): JobHandler<TripBookingRequest, String> {
            val sessionId = "session-${factoryCalls.incrementAndGet()}"
            return SessionScopedBookingHandler(service, sessionId, handlerCalls)
        }
    }

    /**
     * Single-shot booking handler used to verify the reified `registerHandler` extension path. Runs
     * the full four-step travel-booking workflow and reports the final summary via [observed] so
     * the test can assert the reified path produces the same end-to-end result as the Class-form
     * factory overload.
     */
    private class OneShotBookingHandler(
        private val service: BookingService,
        private val observed: CompletableFuture<String>,
    ) : JobHandler<TripBookingRequest, String> {
        override fun execute(input: TripBookingRequest, ctx: JobContext): String {
            ctx.run<PassportValidation>("validate-passport") {
                service.validatePassport(input.userId)
            }
            val flight = ctx.run<FlightBooking>("book-flight") { service.bookFlight(input.userId) }
            ctx.run<PaymentReceipt>("charge-payment") { service.chargePayment(input.userId) }
            val notification =
                ctx.run<String>("send-itinerary") { service.sendItinerary(input.userId) }
            val summary = "${flight.bookingId}/$notification"
            observed.complete(summary)
            return summary
        }
    }

    @Test
    fun `factory create() is invoked once per job execution`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val n = 5
        val service = FakeBookingService()
        val factoryCalls = AtomicInteger(0)
        val handlerCalls = AtomicInteger(0)

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

        val worker = trackedWorker(workerId)
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            BookingFactory(service, factoryCalls, handlerCalls),
        )
        worker.start()

        for (jobId in jobIds) {
            Awaits.awaitTrue(
                timeout = Duration.ofSeconds(60),
                description = "job $jobId to complete",
            ) {
                manager.getEvents(jobId).any { it.eventType == JobEventType.COMPLETED }
            }
        }

        // factory.create() must be invoked exactly once per job execution. handlerCalls
        // matches because each handler instance is used for exactly one execute call.
        assertThat(factoryCalls.get()).isEqualTo(n)
        assertThat(handlerCalls.get()).isEqualTo(n)
    }

    @Test
    fun `reified registerHandlerFactory extension wires through to the framework`() {
        val type = uniqueType()
        val workerId = uniqueWorkerId()
        val request = TripBookingRequest(userId = "rider-42", destination = "NYC")
        val service = FakeBookingService()

        val req =
            CreateJobRequest.builder(TripBookingRequest::class.java)
                .type(type)
                .idempotencyKey(uniqueKey())
                .input(request)
                .build()
        val job = manager.createJob(req)

        val observed = CompletableFuture<String>()
        val worker = trackedWorker(workerId)
        // The reified Kotlin extension — proves the reified path produces the same end-to-end
        // result as the Class-form factory overload from Java. The factory body can still be a
        // lambda since `HandlerFactory.create()` has no args; what we care about is that the
        // *handler* it returns is now an explicit named class.
        worker.registerHandler(
            type,
            TripBookingRequest::class.java,
            String::class.java,
            HandlerFactory<TripBookingRequest, String> { OneShotBookingHandler(service, observed) },
        )
        worker.start()

        val summary = observed.get(30, TimeUnit.SECONDS)
        assertThat(summary).isEqualTo("flight-rider-42/notif-rider-42")
        Awaits.awaitTrue(timeout = Duration.ofSeconds(30), description = "job to complete") {
            manager.getEvents(job.id).any { it.eventType == JobEventType.COMPLETED }
        }
    }
}
