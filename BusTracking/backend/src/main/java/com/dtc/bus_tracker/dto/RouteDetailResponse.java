package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class RouteDetailResponse {
    private Long id;
    private String routeCode;
    private String name;
    /** True if any trip on this route is marked wheelchair-accessible in the GTFS feed. */
    private Boolean wheelchairAccessible;
    /** Reserved wheelchair spaces on board when wheelchairAccessible is true, else 0. */
    private Integer wheelchairSpaces;
    /** Ordered stops with a polyline implied by stop lat/lng in sequence. */
    private List<RouteStopInfo> stops;
    private String trackedVehicleId;
    private Double vehicleLatitude;
    private Double vehicleLongitude;
    private Integer etaToNextStopMinutes;
}
