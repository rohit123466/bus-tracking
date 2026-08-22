package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BusDetailResponse {
    private String vehicleId;
    private String routeCode;
    private String routeName;
    private Double latitude;
    private Double longitude;
    private Double speedKmh;
    private Double bearing;
    private Long lastUpdatedEpochSeconds;
    private Boolean wheelchairAccessible;
    /** Reserved wheelchair spaces on board when wheelchairAccessible is true, else 0. */
    private Integer wheelchairSpaces;
    private Boolean wheelchairSpaceAvailable;
    /** Current/next/remaining stops and the full route, derived from GTFS stop_times order. */
    private RouteDetailResponse route;
}
