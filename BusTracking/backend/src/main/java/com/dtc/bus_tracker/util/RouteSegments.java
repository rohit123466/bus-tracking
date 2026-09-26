package com.dtc.bus_tracker.util;

/**
 * Route-segment arithmetic on trip stop sequences (stop_times.stop_sequence),
 * never on stop names. A journey from stop sequence f to t occupies the
 * half-open range [f, t): the passenger holds the seat on every hop from f
 * up to, but not beyond, t - at t they get down and the seat is free again.
 */
public final class RouteSegments {

    private RouteSegments() {
    }

    /**
     * Two journeys need the same seat at the same time iff
     * newFrom &lt; existingTo AND newTo &gt; existingFrom.
     * A&rarr;C and C&rarr;E touch at C but do not overlap.
     */
    public static boolean overlaps(int newFrom, int newTo, int existingFrom, int existingTo) {
        return newFrom < existingTo && newTo > existingFrom;
    }

    /** Whether a journey [from, to) has the passenger on board while the bus is at stop {@code sequence}. */
    public static boolean onBoardAt(int from, int to, int sequence) {
        return from <= sequence && sequence < to;
    }
}
