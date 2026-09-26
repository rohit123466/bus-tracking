package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.TicketType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class FareQuote {
    private BigDecimal amount;
    private String currency;
    private TicketType ticketType;
    private String ticketTypeLabel;
    /** e.g. "Demo fare", "Demo accessibility fare". */
    private String fareLabel;
    private String disclaimer;
}
