package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A ticket holds one seat on one trip for the half-open stop range
 * [fromSequence, toSequence) - the passenger sits from boarding until the
 * destination stop, where the seat is free again for the rest of the route.
 * Sequences are copied from the trip's stop_times so overlap checks are pure
 * integer comparisons (never stop-name comparisons).
 */
@Entity
@Table(name = "tickets", indexes = {
        @Index(name = "idx_ticket_trip_seat", columnList = "trip_id, seat_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_number", unique = true)
    private String ticketNumber;

    /** Client-generated idempotency key, so a double-submitted sale can't issue two tickets. */
    @Column(name = "request_id", unique = true)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "passenger_id", nullable = false)
    private Passenger passenger;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_stop_id", nullable = false)
    private Stop fromStop;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_stop_id", nullable = false)
    private Stop toStop;

    @Column(name = "from_sequence", nullable = false)
    private Integer fromSequence;

    @Column(name = "to_sequence", nullable = false)
    private Integer toSequence;

    /** Where the passenger actually got down; set when the ticket is completed. */
    @Column(name = "alighted_sequence")
    private Integer alightedSequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "passenger_category", nullable = false)
    private PassengerCategory passengerCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false)
    private TicketType ticketType;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal fare;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "issued_by")
    private String issuedBy;

    /** Stop sequence (exclusive) up to which this ticket holds its seat; null when it holds none. */
    public Integer occupiedUntilSequence() {
        return switch (status) {
            case ACTIVE -> toSequence;
            case COMPLETED -> alightedSequence != null ? alightedSequence : toSequence;
            case CANCELLED -> null;
        };
    }
}
