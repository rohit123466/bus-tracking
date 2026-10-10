package com.dtc.bus_tracker.util;

/** Legacy straight-line estimate. Missing or stationary speed cannot produce a reliable ETA. */
public final class EtaCalculator {
    private EtaCalculator() {}
    public static Integer estimateMinutes(double distanceMeters, Double speedKmh) {
        if (!Double.isFinite(distanceMeters) || distanceMeters < 0 || speedKmh == null
                || !Double.isFinite(speedKmh) || speedKmh < 3 || speedKmh > 120) return null;
        return (int) Math.ceil(distanceMeters / (speedKmh * 1000 / 60));
    }
}
