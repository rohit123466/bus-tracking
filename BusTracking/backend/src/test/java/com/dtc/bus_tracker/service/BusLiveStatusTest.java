package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import org.junit.jupiter.api.Test;

import static com.dtc.bus_tracker.service.BusLiveStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class BusLiveStatusTest {

    static final long NOW = 1_800_000_000L;

    static BusLocationEvent fix(Long ageSeconds, Double speed) {
        return BusLocationEvent.builder().vehicleId("V1").timestamp(ageSeconds == null ? null : NOW - ageSeconds)
                .speedKmh(speed).build();
    }

    @Test void noPositionIsNoData() {
        assertEquals(NO_DATA, of(null, NOW));
    }

    @Test void staleAfterExactly120Seconds() {
        assertEquals(MOVING, of(fix(120L, 30.0), NOW));
        assertEquals(STALE, of(fix(121L, 30.0), NOW));
        assertEquals(STALE, of(fix(null, 30.0), NOW));
    }

    @Test void slowBelowFiveKmhOnlyWhileReporting() {
        assertEquals(SLOW, of(fix(10L, 4.9), NOW));
        assertEquals(MOVING, of(fix(10L, 5.0), NOW));
        assertEquals(STALE, of(fix(500L, 0.0), NOW));
    }

    @Test void unknownSpeedCountsAsMovingNotSlow() {
        assertEquals(MOVING, of(fix(10L, null), NOW));
    }

    @Test void onlyMovingAndSlowAreReporting() {
        assertTrue(MOVING.reporting());
        assertTrue(SLOW.reporting());
        assertFalse(STALE.reporting());
        assertFalse(NO_DATA.reporting());
    }
}
