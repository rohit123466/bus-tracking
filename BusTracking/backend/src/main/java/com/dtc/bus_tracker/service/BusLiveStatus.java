package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;

/**
 * One bus's state as the admin console shows it, derived from its latest
 * position in {@link BusLocationStore}. The admin stats, the fleet list and the
 * live map all classify through here so their counts agree.
 *
 * <p>SLOW is "reporting but under {@value #SLOW_SPEED_KMH} km/h" - a proxy for
 * being stuck in traffic or parked, not a comparison against a timetable (this
 * project has no schedule-adherence data).
 */
public enum BusLiveStatus {
    /** Reporting, and either at or above the slow threshold or with no speed reported. */
    MOVING,
    /** Reporting, but slower than {@value #SLOW_SPEED_KMH} km/h. */
    SLOW,
    /** Last position is more than {@value #STALE_AFTER_SECONDS} s old (or has no timestamp). */
    STALE,
    /** No position in the store at all (not seen since the store was last cleared). */
    NO_DATA;

    public static final long STALE_AFTER_SECONDS = 120;
    public static final double SLOW_SPEED_KMH = 5.0;

    public static BusLiveStatus of(BusLocationEvent event, long nowEpochSeconds) {
        if (event == null) return NO_DATA;
        if (!isReporting(event, nowEpochSeconds)) return STALE;
        return event.getSpeedKmh() != null && event.getSpeedKmh() < SLOW_SPEED_KMH ? SLOW : MOVING;
    }

    public static boolean isReporting(BusLocationEvent event, long nowEpochSeconds) {
        return event.getTimestamp() != null && (nowEpochSeconds - event.getTimestamp()) <= STALE_AFTER_SECONDS;
    }

    public boolean reporting() {
        return this == MOVING || this == SLOW;
    }
}
