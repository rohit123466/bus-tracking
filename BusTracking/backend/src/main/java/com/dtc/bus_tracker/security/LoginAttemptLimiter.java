package com.dtc.bus_tracker.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing on the admin login: after {@code maxFailures}
 * failed attempts from one client address inside {@code window}, further
 * attempts from that address are refused until the window has passed. A
 * successful login clears the address's count.
 *
 * <p>In-memory and per instance, which is enough for the single API instance
 * this app runs as; several instances would need a shared store (Redis).
 */
@Component
public class LoginAttemptLimiter {

    /** Upper bound on tracked addresses, so a flood of spoofed sources cannot exhaust memory. */
    static final int MAX_TRACKED_CLIENTS = 10_000;

    private record Failures(int count, Instant windowStart) {}

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    @Autowired
    public LoginAttemptLimiter(@Value("${auth.login.max-failures:5}") int maxFailures,
                               @Value("${auth.login.lockout-minutes:15}") long lockoutMinutes) {
        this(maxFailures, Duration.ofMinutes(lockoutMinutes), Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public boolean isBlocked(String client) {
        Failures f = failures.get(client);
        if (f == null) return false;
        if (expired(f)) {
            failures.remove(client, f);
            return false;
        }
        return f.count() >= maxFailures;
    }

    public void recordFailure(String client) {
        if (failures.size() >= MAX_TRACKED_CLIENTS) {
            failures.values().removeIf(this::expired);
        }
        failures.compute(client, (k, f) -> f == null || expired(f)
                ? new Failures(1, clock.instant())
                : new Failures(f.count() + 1, f.windowStart()));
    }

    public void recordSuccess(String client) {
        failures.remove(client);
    }

    private boolean expired(Failures f) {
        return clock.instant().isAfter(f.windowStart().plus(window));
    }
}
