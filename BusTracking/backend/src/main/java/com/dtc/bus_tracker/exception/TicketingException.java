package com.dtc.bus_tracker.exception;

import org.springframework.http.HttpStatus;

/**
 * A ticketing rule was violated. The message is shown to the conductor as-is,
 * so it should say exactly what is wrong (e.g. "Seat 05 is already occupied
 * from B to C.") rather than a generic failure.
 */
public class TicketingException extends RuntimeException {

    private final HttpStatus status;

    public TicketingException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static TicketingException badRequest(String message) {
        return new TicketingException(HttpStatus.BAD_REQUEST, message);
    }

    public static TicketingException conflict(String message) {
        return new TicketingException(HttpStatus.CONFLICT, message);
    }
}
