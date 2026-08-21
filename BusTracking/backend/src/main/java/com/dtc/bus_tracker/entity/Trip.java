package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Trip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", unique = true, nullable = false)
    private String tripId; // GTFS trip_id, e.g. "12345"

    @Column(name = "service_id")
    private String serviceId;

    @Column(name = "shape_id")
    private String shapeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bus_id")
    private Bus bus; // nullable now — only set when real-time TripUpdates assigns a bus

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    private TripStatus status;

    /**
     * GTFS trips.txt wheelchair_accessible == "1". Null when the feed omits the
     * column; a boxed type is required here (not primitive boolean) since
     * `ddl-auto=update` adds this column to an already-seeded database without
     * backfilling existing rows, leaving them NULL.
     */
    @Column(name = "wheelchair_accessible")
    private Boolean wheelchairAccessible;
}