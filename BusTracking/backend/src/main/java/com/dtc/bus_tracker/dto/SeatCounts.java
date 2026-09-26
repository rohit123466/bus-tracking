package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

/** Seat tallies for one route segment. Every number is computed from tickets, never stored. */
@Getter
@Builder
public class SeatCounts {
    /** Passenger seats (regular + priority), excluding wheelchair spaces. */
    private int totalSeats;
    private int prioritySeats;
    private int wheelchairSpaces;
    /** Free regular seats (including vacated ones). */
    private int availableRegular;
    /** Free priority seats. */
    private int availablePriority;
    private int availableWheelchair;
    /** Seats (not wheelchair spaces) held by an overlapping ticket. */
    private int occupiedSeats;
    private int occupiedWheelchair;
    private int unavailableSeats;
    /** Free places the requested passenger category may actually book. */
    private int availableForCategory;
}
