package org.zeplinko.logplay.client.integration

/**
 * Service contract used by integration-test [org.zeplinko.logplay.client.handler.JobHandler]s to
 * model a multi-step travel-booking workflow. Each method is intended to be invoked inside
 * `ctx.run(...)` so its result is durably checkpointed.
 *
 * Most methods return Jackson-serializable data classes so the JSON codec is exercised on every
 * checkpoint; a couple return raw [String] ids to keep coverage mixed across codec shapes.
 */
interface BookingService {
    fun validatePassport(userId: String): PassportValidation

    fun bookFlight(userId: String): FlightBooking

    fun bookHotel(userId: String): HotelReservation

    fun bookCab(userId: String): String

    fun chargePayment(userId: String): PaymentReceipt

    fun sendItinerary(userId: String): String

    fun cancelBooking(bookingId: String)
}

data class TripBookingRequest(val userId: String, val destination: String)

data class PassportValidation(
    val userId: String,
    val documentNumber: String,
    val validUntilEpochDay: Long,
)

data class FlightBooking(
    val bookingId: String,
    val flightNumber: String,
    val seat: String,
    val departureEpochMillis: Long,
)

data class HotelReservation(val reservationId: String, val hotelName: String, val nights: Int)

data class PaymentReceipt(val transactionId: String, val amountCents: Int, val currency: String)
