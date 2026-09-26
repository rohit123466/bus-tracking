package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

/** Free places on one stop-to-stop hop of the journey. */
@Getter
@Builder
public class SegmentAvailability {
    private String fromStop;
    private String toStop;
    private int fromSequence;
    private int toSequence;
    private int availableSeats;
    private int availablePriority;
    private int availableWheelchair;
    private int occupiedSeats;
    private boolean soldOut;
}
