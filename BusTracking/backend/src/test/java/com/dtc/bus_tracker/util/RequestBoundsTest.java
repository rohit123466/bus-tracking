package com.dtc.bus_tracker.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RequestBoundsTest {

    @Test void clampsLimitsIntoAUsableRange() {
        assertEquals(1, RequestBounds.limit(-1));
        assertEquals(10, RequestBounds.limit(10));
        assertEquals(RequestBounds.MAX_LIMIT, RequestBounds.limit(1_000_000));
    }

    @Test void clampsPagingSoHugeOrNegativeValuesCannotFail() {
        assertEquals(0, RequestBounds.page(-5));
        assertEquals(1, RequestBounds.pageSize(0));
        assertEquals(RequestBounds.MAX_PAGE_SIZE, RequestBounds.pageSize(10_000_000));
    }

    @Test void clampsRadius() {
        assertEquals(0, RequestBounds.radius(-100));
        assertEquals(0, RequestBounds.radius(Double.NaN));
        assertEquals(RequestBounds.MAX_RADIUS_METERS, RequestBounds.radius(Double.POSITIVE_INFINITY));
    }
}
