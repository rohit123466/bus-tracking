package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RouteStat {
    /** GTFS route_id, as the live feed reports it. */
    private String routeCode;
    /** Public route number (GTFS route_short_name); null when the route is not in the imported data. */
    private String routeName;
    private long activeBusCount;
}
