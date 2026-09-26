package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.BusType;
import com.dtc.bus_tracker.entity.PassengerCategory;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** Seat map of one bus for one requested route segment and passenger category. */
@Getter
@Builder
public class SeatMapResponse {
    private String busNumber;
    private BusType busType;
    private String busTypeLabel;
    private String routeCode;
    private String routeName;
    private List<TicketingStopView> stops;
    private TicketingStopView fromStop;
    private TicketingStopView toStop;
    private PassengerCategory category;
    private String categoryLabel;
    private int layoutRows;
    private int layoutColumns;
    private int aisleColumn;
    private List<SeatView> seats;
    private SeatCounts counts;
    private boolean soldOut;
    private String soldOutMessage;
    private FareQuote fare;
}
