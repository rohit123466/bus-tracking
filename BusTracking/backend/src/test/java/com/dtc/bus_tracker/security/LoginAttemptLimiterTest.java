package com.dtc.bus_tracker.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptLimiterTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(3, Duration.ofMinutes(15), clock);

    @Test void blocksAfterTooManyFailures() {
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertFalse(limiter.isBlocked("1.2.3.4"));
        limiter.recordFailure("1.2.3.4");
        assertTrue(limiter.isBlocked("1.2.3.4"));
    }

    @Test void otherClientsAreNotAffected() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("1.2.3.4");
        assertFalse(limiter.isBlocked("5.6.7.8"));
    }

    @Test void unblocksOnceTheWindowHasPassed() {
        for (int i = 0; i < 3; i++) limiter.recordFailure("1.2.3.4");
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertFalse(limiter.isBlocked("1.2.3.4"));
        limiter.recordFailure("1.2.3.4");
        assertFalse(limiter.isBlocked("1.2.3.4"), "a failure after expiry starts a fresh count");
    }

    @Test void successClearsTheCount() {
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordSuccess("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertFalse(limiter.isBlocked("1.2.3.4"));
    }
}
