package com.dtc.bus_tracker.dto;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusLocationEvent implements Serializable {
    private String vehicleId;
    private Double latitude;
    private Double longitude;
    private String routeId;
    /**
     * GTFS trip_id from the realtime feed's TripDescriptor.
     * May be blank when the feed omits it. Used for route_id cross-validation
     * in DtcIngestionService and for diagnostic logging.
     */
    private String tripId;
    private Long timestamp;
    /** km/h. Null when the source feed doesn't report it. */
    private Double speedKmh;
    /** Compass degrees, 0-360. Null when the source feed doesn't report it. */
    private Double bearing;
    /** Wheelchair space availability status. True = available, False = not available, Null = unknown */
    private Boolean wheelchairSpaceAvailable;
}