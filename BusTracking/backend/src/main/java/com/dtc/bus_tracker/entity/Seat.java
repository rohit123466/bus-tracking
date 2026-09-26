package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One persisted seat (or wheelchair space) of a bus. Seats are generated once
 * from the bus type's configured layout and keep their id/number for the life
 * of the bus, so a seat map never renumbers between page loads.
 *
 * There is deliberately no booked/free status column here: occupancy depends
 * on the route segment being asked about and is derived from tickets.
 */
@Entity
@Table(name = "seats", uniqueConstraints = {
        @UniqueConstraint(name = "uk_seat_bus_number", columnNames = {"bus_id", "seat_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    /** "01".."35" for seats, "WC1" for wheelchair spaces. */
    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false)
    private SeatType seatType;

    /** Grid position for the seat map: 5 columns, column 2 is the aisle. */
    @Column(name = "row_index", nullable = false)
    private Integer rowIndex;

    @Column(name = "column_index", nullable = false)
    private Integer columnIndex;

    @Column(name = "row_span", nullable = false)
    private Integer rowSpan;

    @Column(name = "column_span", nullable = false)
    private Integer columnSpan;

    /** False for a seat taken out of service (damaged etc.); never sold. */
    @Column(name = "in_service", nullable = false)
    private Boolean inService;

    public boolean isWheelchairSpace() {
        return seatType == SeatType.WHEELCHAIR;
    }
}
