package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.SeatType;
import com.dtc.bus_tracker.entity.TicketStatus;
import com.dtc.bus_tracker.entity.TicketType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class TicketResponse {
    private String ticketNumber;
    private String busNumber;
    private String routeCode;
    private String seatNumber;
    private SeatType seatType;
    private Long passengerId;
    private String passengerName;
    private PassengerCategory passengerCategory;
    private String passengerCategoryLabel;
    private TicketType ticketType;
    private String ticketTypeLabel;
    private Long fromStopId;
    private String fromStop;
    private int fromSequence;
    private Long toStopId;
    private String toStop;
    private int toSequence;
    private String alightedAtStop;
    private BigDecimal fare;
    private String currency;
    private String fareLabel;
    private TicketStatus status;
    /** Whether the passenger is on board while the bus is at its current stop. */
    private boolean onBoardNow;
    private LocalDateTime issuedAt;
    private LocalDateTime completedAt;
    private String issuedBy;
}
