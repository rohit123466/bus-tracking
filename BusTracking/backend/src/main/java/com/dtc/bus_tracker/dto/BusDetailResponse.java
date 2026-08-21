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
<<<<<<< HEAD
    private Boolean wheelchairAccessible;
    /** Reserved wheelchair spaces on board when wheelchairAccessible is true, else 0. */
    private Integer wheelchairSpaces;
=======
    private Boolean wheelchairSpaceAvailable;
>>>>>>> 427d9c8f074a1a43e801c6162624d66cf4cd129b
    /** Current/next/remaining stops and the full route, derived from GTFS stop_times order. */
    private RouteDetailResponse route;
}
