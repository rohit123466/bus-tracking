package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.BusStatus;
import com.dtc.bus_tracker.entity.BusType;
import com.dtc.bus_tracker.service.BusLiveStatus;
import lombok.Builder;
import lombok.Getter;

/** One row of the admin fleet list: the registered bus joined with its latest live position. */
@Getter
@Builder
public class FleetBusResponse {
    private String vehicleId;
    private String registrationNumber;
    /** Null for live-feed buses: the feeds publish no seat configuration. */
    private BusType busType;
    /** Registry status. Ingestion marks every bus it sees ACTIVE, so read liveStatus for "is it running". */
    private BusStatus status;
    private Boolean wheelchairAccessible;
    private Boolean cameraInstalled;
    /** GTFS route_id. */
    private String routeCode;
    /** Public route number; null when unknown. */
    private String routeName;

    private BusLiveStatus liveStatus;
    /** Epoch seconds of the latest GPS fix; null when there is none. */
    private Long lastSeen;
    private Double speedKmh;
    private Double bearing;
    private Double latitude;
    private Double longitude;
    private Boolean wheelchairSpaceAvailable;
}
