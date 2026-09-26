package com.dtc.bus_tracker.entity;

public enum TicketStatus {
    /** Issued; the seat is held for [fromSequence, toSequence). */
    ACTIVE,
    /** Passenger got down; the seat is held only for [fromSequence, alightedSequence). */
    COMPLETED,
    /** Voided by the conductor; holds no seat at all. */
    CANCELLED
}
