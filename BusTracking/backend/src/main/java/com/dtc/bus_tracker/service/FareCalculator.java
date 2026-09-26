package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.dto.FareQuote;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.SeatType;
import com.dtc.bus_tracker.entity.TicketType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Demo fares from the configurable "ticketing.fare.*" properties. The project
 * has no official DTC fare table, so every quote is labelled as a demo value.
 */
@Service
@RequiredArgsConstructor
public class FareCalculator {

    public static final String DISCLAIMER = "Demo value for this project - not an official DTC fare or concession.";

    private final TicketingProperties properties;

    public FareQuote quote(PassengerCategory category, SeatType seatType, int stopsTravelled) {
        TicketingProperties.Fare fare = properties.getFare();
        BigDecimal standard = fare.getBase().add(fare.getPerStop().multiply(BigDecimal.valueOf(stopsTravelled)));

        BigDecimal amount;
        String label;
        switch (category) {
            case SENIOR_CITIZEN -> {
                BigDecimal payable = BigDecimal.valueOf(100L - fare.getSeniorConcessionPercent());
                amount = standard.multiply(payable).divide(BigDecimal.valueOf(100));
                label = "Demo senior citizen concession fare";
            }
            case DIVYANGJAN -> {
                amount = fare.getAccessibilityFare();
                label = "Demo accessibility fare";
            }
            default -> {
                amount = standard;
                label = "Demo fare";
            }
        }

        TicketType ticketType = TicketType.of(category, seatType);
        return FareQuote.builder()
                .amount(amount.setScale(2, RoundingMode.HALF_UP))
                .currency(fare.getCurrency())
                .ticketType(ticketType)
                .ticketTypeLabel(ticketType.getLabel())
                .fareLabel(label)
                .disclaimer(DISCLAIMER)
                .build();
    }
}
