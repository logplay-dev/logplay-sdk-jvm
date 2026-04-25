package org.zeplinko.logplay.client.integration

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic in-memory [BookingService] for integration tests.
 *
 * Outputs are derived from the `userId` argument (no randomness, no wall-clock) so tests can assert
 * exact equality on the data round-tripped through checkpoint JSON. Each method increments its own
 * call counter and consults a fault-injection queue — the first item from the queue (if any) is the
 * exception thrown on this call. This lets retry/replay tests script "fail the next N invocations"
 * without subclassing the service or wiring extra state through handlers.
 *
 * The class is thread-safe: counters are atomic and fault queues are concurrent. Handlers may run
 * on worker threads, and a single fake can be shared across handlers within one test.
 */
class FakeBookingService : BookingService {

    val validatePassportCalls: AtomicInteger = AtomicInteger(0)
    val bookFlightCalls: AtomicInteger = AtomicInteger(0)
    val bookHotelCalls: AtomicInteger = AtomicInteger(0)
    val bookCabCalls: AtomicInteger = AtomicInteger(0)
    val chargePaymentCalls: AtomicInteger = AtomicInteger(0)
    val sendItineraryCalls: AtomicInteger = AtomicInteger(0)
    val cancelBookingCalls: AtomicInteger = AtomicInteger(0)

    private val validatePassportFailures = ConcurrentLinkedQueue<RuntimeException>()
    private val bookFlightFailures = ConcurrentLinkedQueue<RuntimeException>()
    private val bookHotelFailures = ConcurrentLinkedQueue<RuntimeException>()
    private val bookCabFailures = ConcurrentLinkedQueue<RuntimeException>()
    private val chargePaymentFailures = ConcurrentLinkedQueue<RuntimeException>()
    private val sendItineraryFailures = ConcurrentLinkedQueue<RuntimeException>()
    private val cancelBookingFailures = ConcurrentLinkedQueue<RuntimeException>()

    override fun validatePassport(userId: String): PassportValidation {
        validatePassportCalls.incrementAndGet()
        validatePassportFailures.poll()?.let { throw it }
        return PassportValidation(userId, "doc-$userId", 0L)
    }

    override fun bookFlight(userId: String): FlightBooking {
        bookFlightCalls.incrementAndGet()
        bookFlightFailures.poll()?.let { throw it }
        return FlightBooking("flight-$userId", "AA-101", "12A", 0L)
    }

    override fun bookHotel(userId: String): HotelReservation {
        bookHotelCalls.incrementAndGet()
        bookHotelFailures.poll()?.let { throw it }
        return HotelReservation("hotel-$userId", "Hilton-$userId", 3)
    }

    override fun bookCab(userId: String): String {
        bookCabCalls.incrementAndGet()
        bookCabFailures.poll()?.let { throw it }
        return "cab-$userId"
    }

    override fun chargePayment(userId: String): PaymentReceipt {
        chargePaymentCalls.incrementAndGet()
        chargePaymentFailures.poll()?.let { throw it }
        return PaymentReceipt("txn-$userId", 1500, "USD")
    }

    override fun sendItinerary(userId: String): String {
        sendItineraryCalls.incrementAndGet()
        sendItineraryFailures.poll()?.let { throw it }
        return "notif-$userId"
    }

    override fun cancelBooking(bookingId: String) {
        cancelBookingCalls.incrementAndGet()
        cancelBookingFailures.poll()?.let { throw it }
    }

    // --- Fault injection ---------------------------------------------------------------------
    // Each enqueued exception is consumed by the next call to that method. To fail N times,
    // call the helper N times (or use the *Times helpers below).

    fun failNextValidatePassport(
        ex: RuntimeException = IllegalStateException("validatePassport failed")
    ) {
        validatePassportFailures.add(ex)
    }

    fun failNextBookFlight(ex: RuntimeException = IllegalStateException("bookFlight failed")) {
        bookFlightFailures.add(ex)
    }

    fun failNextBookHotel(ex: RuntimeException = IllegalStateException("bookHotel failed")) {
        bookHotelFailures.add(ex)
    }

    fun failNextBookCab(ex: RuntimeException = IllegalStateException("bookCab failed")) {
        bookCabFailures.add(ex)
    }

    fun failNextChargePayment(
        ex: RuntimeException = IllegalStateException("chargePayment failed")
    ) {
        chargePaymentFailures.add(ex)
    }

    fun failNextSendItinerary(
        ex: RuntimeException = IllegalStateException("sendItinerary failed")
    ) {
        sendItineraryFailures.add(ex)
    }

    fun failNextCancelBooking(
        ex: RuntimeException = IllegalStateException("cancelBooking failed")
    ) {
        cancelBookingFailures.add(ex)
    }

    fun failChargePaymentAlways(
        ex: RuntimeException = IllegalStateException("chargePayment failed")
    ) {
        // Enough to cover any realistic retry budget without unbounded growth.
        repeat(1024) { chargePaymentFailures.add(ex) }
    }
}
