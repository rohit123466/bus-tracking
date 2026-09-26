package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

/** One stop of a ticketing journey, relative to where the bus is now. */
@Getter
@Builder
public class TicketingStopView {
    private Long stopId;
    private String name;
    private int sequence;
    private StopProgress progress;
}
