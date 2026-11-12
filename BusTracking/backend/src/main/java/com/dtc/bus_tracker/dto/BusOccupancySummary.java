package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.BusType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** Occupancy dashboard card for one ticketing bus, as of the stop it is at now. */
@Getter
@Builder
public class BusOccupancySummary {
    private String busNumber;
    private BusType busType;
    private String busTypeLabel;
    private boolean lowFloor;
    private String routeCode;
    private String routeName;
    private List<TicketingStopView> stops;
    private TicketingStopView currentStop;
    private TicketingStopView nextStop;

    private int totalSeats;
    private int prioritySeats;
    private int wheelchairSpaces;
    private int availableSeats;
    /** Free priority seats right now. */
    private int reservedSeats;
    private int occupiedSeats;
    private int unavailableSeats;
    /** AVAILABLE, OCCUPIED or NOT_FITTED. */
    private String wheelchairStatus;
    private int onBoard;
    private int capacity;
    private double occupancyPercent;

    private long ticketsSold;
    private long activeTickets;
    private long completedTickets;

    private String cameraStatus;
    private boolean cameraSimulated;
    private LocalDateTime cameraLastScanAt;
    private int cameraMismatches;

    /** EMPTY, OCCUPIED or UNKNOWN (no camera, or no reading yet). */
    private String standingStatus;
    /** Passengers standing in the standing area; the conductor is never counted. */
    private int standingPassengers;
    /** AVAILABLE or NOT_AVAILABLE, derived from free seats and the standing area. */
    private String seatAvailability;
    private String seatAvailabilityReason;

    private List<SegmentAvailability> segments;
}
