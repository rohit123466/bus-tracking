package com.dtc.bus_tracker.util;

/**
 * Clamps client-supplied sizes on public endpoints, so a request such as
 * {@code limit=-1} or {@code size=10000000} gets a sensible result instead of a
 * server error or an attempt to serialise the whole table.
 */
public final class RequestBounds {

    public static final double MAX_RADIUS_METERS = 20_000;
    public static final int MAX_LIMIT = 100;
    /** The frontend loads every stop in one page of this size to resolve stop clicks offline. */
    public static final int MAX_PAGE_SIZE = 1000;

    private RequestBounds() {}

    public static double radius(double meters) {
        return Double.isNaN(meters) ? 0 : Math.max(0, Math.min(MAX_RADIUS_METERS, meters));
    }

    public static int limit(int limit) {
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    public static int page(int page) {
        return Math.max(0, page);
    }

    public static int pageSize(int size) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, size));
    }
}
