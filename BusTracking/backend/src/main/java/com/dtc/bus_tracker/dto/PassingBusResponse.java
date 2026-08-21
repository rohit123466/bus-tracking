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
<<<<<<< HEAD
    private Boolean wheelchairAccessible;
    /** Reserved wheelchair spaces on board when wheelchairAccessible is true, else 0. */
    private Integer wheelchairSpaces;
=======
    private Boolean wheelchairSpaceAvailable;
>>>>>>> 427d9c8f074a1a43e801c6162624d66cf4cd129b
}
