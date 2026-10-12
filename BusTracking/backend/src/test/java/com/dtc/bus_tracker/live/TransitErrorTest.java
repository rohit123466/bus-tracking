package com.dtc.bus_tracker.live;
import com.dtc.bus_tracker.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class TransitErrorTest {
    @Test void invalidCoordinatesKeepTheirBadRequestStatus() {
        var controller = new TransitController(null, null, null, null);
        var exception = assertThrows(ResponseStatusException.class, () -> controller.nearest(999, 77));
        var response = new GlobalExceptionHandler().handleResponseStatus(exception);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Coordinates must be in Delhi/NCR", response.getBody().get("message"));
    }
    @Test void missingStopKeepsNotFoundStatus() {
        var service = new LiveTransitService(new TransitIndex(), new RealtimeSnapshot());
        var exception = assertThrows(ResponseStatusException.class, () -> service.stop("missing"));
        assertEquals(HttpStatus.NOT_FOUND, new GlobalExceptionHandler().handleResponseStatus(exception).getStatusCode());
    }
    @Test void unexpectedFailuresAreServerErrorsWithoutInternalDetails() {
        var response = new GlobalExceptionHandler().handleRuntimeException(new IllegalStateException("private details"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse(response.getBody().toString().contains("private details"));
    }
}
