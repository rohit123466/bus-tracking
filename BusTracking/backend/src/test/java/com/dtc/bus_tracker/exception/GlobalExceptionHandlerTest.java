package com.dtc.bus_tracker.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test void missingQueryParameterIsABadRequestNotAServerError() {
        var response = handler.handleGenericException(new MissingServletRequestParameterException("lat", "double"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().get("message").toString().contains("lat"));
    }

    @Test void wrongHttpMethodKeepsItsStatus() {
        var response = handler.handleGenericException(new HttpRequestMethodNotSupportedException("DELETE"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
    }

    @Test void unexpectedCheckedExceptionsAreServerErrorsWithoutDetails() {
        var response = handler.handleGenericException(new java.io.IOException("disk path /secret"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().toString().contains("/secret"));
    }
}
