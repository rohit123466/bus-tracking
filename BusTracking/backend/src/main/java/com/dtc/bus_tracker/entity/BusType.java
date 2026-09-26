package com.dtc.bus_tracker.entity;

/**
 * Seat-layout family for ticketing. Only buses taking part in seat-level
 * ticketing carry a type; live-feed buses leave it null since the GTFS/OTD
 * feeds publish no seat configuration.
 */
public enum BusType {
    LOW_FLOOR("Low Floor"),
    STANDARD("Standard");

    private final String label;

    BusType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
