package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RouteStopInfo {
    private Long stopId;
    private String stopCode;
    private String name;
    private Double latitude;
    private Double longitude;
    private int sequence;
    /** GTFS stops.txt wheelchair_boarding == "1". Null when the feed doesn't say. */
    private Boolean wheelchairBoarding;
    /** Null when the route detail was requested without a vehicleId to track. */
    private StopProgress progress;
}
