package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PassingBusResponse {
    private String vehicleId;
    private String routeCode;
    private String routeName;
    private String stopName;
    private String destination;
    private Double distanceToStopMeters;
    private Integer etaMinutes;
    private Boolean wheelchairAccessible;
    /** Reserved wheelchair spaces on board when wheelchairAccessible is true, else 0. */
    private Integer wheelchairSpaces;
}
