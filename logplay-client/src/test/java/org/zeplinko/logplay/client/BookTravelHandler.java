package org.zeplinko.logplay.client;

import org.zeplinko.logplay.client.handler.JobContext;
import org.zeplinko.logplay.client.handler.JobHandler;
import org.zeplinko.logplay.client.integration.BookingService;
import org.zeplinko.logplay.client.integration.FlightBooking;
import org.zeplinko.logplay.client.integration.HotelReservation;
import org.zeplinko.logplay.client.integration.PassportValidation;
import org.zeplinko.logplay.client.integration.PaymentReceipt;
import org.zeplinko.logplay.client.integration.TripBookingRequest;

/**
 * Compile-time guarantee that a multi-step durable handler is ergonomic from Java. Mirrors the
 * Kotlin integration tests' shape: takes a service via constructor injection, drives a multi-step
 * travel-booking workflow, and uses the {@code Class<T>} form of {@code ctx.run} for typed JSON
 * checkpoints. If Java's view of the SDK breaks (SAM conversion, generic inference), this stops
 * compiling.
 */
public class BookTravelHandler implements JobHandler<TripBookingRequest, String> {

    private final BookingService service;

    public BookTravelHandler(BookingService service) {
        this.service = service;
    }

    @Override
    public String execute(TripBookingRequest input, JobContext ctx) throws Exception {
        String userId = input.getUserId();

        PassportValidation passport =
                ctx.run("validate-passport", PassportValidation.class, () -> service.validatePassport(userId));
        FlightBooking flight =
                ctx.run("book-flight", FlightBooking.class, () -> service.bookFlight(userId));
        HotelReservation hotel =
                ctx.run("book-hotel", HotelReservation.class, () -> service.bookHotel(userId));
        String cab = ctx.run("book-cab", String.class, () -> service.bookCab(userId));
        PaymentReceipt receipt =
                ctx.run("charge-payment", PaymentReceipt.class, () -> service.chargePayment(userId));
        String notification =
                ctx.run("send-itinerary", String.class, () -> service.sendItinerary(userId));

        return passport.getDocumentNumber()
                + "/" + flight.getBookingId()
                + "/" + hotel.getReservationId()
                + "/" + cab
                + "/" + receipt.getTransactionId()
                + "/" + notification;
    }
}
