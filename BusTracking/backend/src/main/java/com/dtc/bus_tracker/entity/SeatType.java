package com.dtc.bus_tracker.entity;

public enum SeatType {
    /** Any passenger category may book it. */
    REGULAR,
    /** Priority seat: Senior Citizen and Divyangjan tickets only. */
    PRIORITY,
    /** Dedicated wheelchair space (low-floor buses): Divyangjan tickets only. Not a seat. */
    WHEELCHAIR
}
