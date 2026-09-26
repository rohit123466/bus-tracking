package com.dtc.bus_tracker.config;

import com.dtc.bus_tracker.entity.BusType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Demo configuration for seat-level ticketing (see the "ticketing.*" keys in
 * application.properties). None of these values come from DTC: the GTFS/OTD
 * feeds publish no seat layouts or fares, so capacity and fares here are
 * configurable demo values, not official figures.
 */
@Component
@ConfigurationProperties(prefix = "ticketing")
@Getter
@Setter
public class TicketingProperties {

    private Layout lowFloor = new Layout(35, 4, 1);
    private Layout standard = new Layout(32, 4, 0);
    private Fare fare = new Fare();
    private Camera camera = new Camera();

    public Layout layoutFor(BusType busType) {
        return switch (busType) {
            case LOW_FLOOR -> lowFloor;
            case STANDARD -> standard;
        };
    }

    @Getter
    @Setter
    public static class Layout {
        /** Passenger seats, including priority seats. */
        private int seats;
        /** How many of those seats (nearest the front) are priority seats. */
        private int prioritySeats;
        /** Dedicated wheelchair spaces - in addition to, not part of, the seats. */
        private int wheelchairSpaces;

        public Layout() {
        }

        public Layout(int seats, int prioritySeats, int wheelchairSpaces) {
            this.seats = seats;
            this.prioritySeats = prioritySeats;
            this.wheelchairSpaces = wheelchairSpaces;
        }
    }

    @Getter
    @Setter
    public static class Fare {
        private String currency = "INR";
        /** Demo fare = base + perStop x stops travelled. */
        private BigDecimal base = new BigDecimal("5.00");
        private BigDecimal perStop = new BigDecimal("5.00");
        /** Demo concession for Senior Citizen tickets, in percent off the demo fare. */
        private int seniorConcessionPercent = 50;
        /** Flat "Demo accessibility fare" for Divyangjan tickets. */
        private BigDecimal accessibilityFare = new BigDecimal("5.00");
    }

    @Getter
    @Setter
    public static class Camera {
        /** Chance a simulated scan disagrees with the ticket data for a seat. */
        private double simulatedMismatchRate = 0.08;
    }
}
