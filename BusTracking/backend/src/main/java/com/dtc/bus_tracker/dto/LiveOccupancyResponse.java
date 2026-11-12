package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.service.CameraOccupancyProvider.FeedPreset;
import com.dtc.bus_tracker.service.SeatAvailabilityIndicator;
import com.dtc.bus_tracker.service.StandingZoneTracker;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the conductor console shows live for one bus, computed per
 * request: trip and conductor, seats (from tickets), the standing area (from
 * the camera), the seat-availability indicator derived from both, and ticket
 * sales. Nothing here is stored.
 */
@Getter
@Builder
// Unset fields (no trip running, no camera, no ticket yet) are left out of the JSON.
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public class LiveOccupancyResponse {
    private String busNumber;
    private String busTypeLabel;
    private String routeCode;
    private String routeName;
    private String origin;
    private String destination;
    private LocalDateTime tripStartedAt;
    private String currentStop;
    private String nextStop;

    /** Logged-in conductor. */
    private String conductorId;
    private long conductorTicketsIssued;
    private Instant conductorLastTicketAt;
    /** The conductor is in the standing area right now (never counted as a passenger). */
    private boolean conductorInStandingArea;

    private int totalSeats;
    private int occupiedSeats;
    /** Free regular + priority seats on the current hop. */
    private int availableSeats;
    private int wheelchairSpaces;
    /** AVAILABLE, OCCUPIED or NOT_FITTED. */
    private String wheelchairStatus;
    private int capacity;
    /** Ticketed passengers on board at the current stop. */
    private int seatedPassengers;
    private int standingPassengers;
    private int passengersOnBoard;
    private double occupancyPercent;

    private SeatAvailabilityIndicator.Availability seatAvailability;
    private SeatAvailabilityIndicator.Basis seatAvailabilityBasis;
    private String seatAvailabilityReason;

    private boolean cameraInstalled;
    /** CONNECTED, DISCONNECTED or NOT_INSTALLED. */
    private String cameraStatus;
    /** True while frames come from the preset feed rather than camera hardware. */
    private boolean presetFeed;
    private String activePresetId;
    private List<FeedPreset> presets;
    private StandingZoneTracker.Snapshot standingArea;

    private long ticketsSold;
    private long activeTickets;
    private long completedTickets;
    private long cancelledTickets;
    private BigDecimal fareCollected;
    private String currency;
    private String lastTicketNumber;
    private Instant lastTicketAt;

    private Instant updatedAt;
}
