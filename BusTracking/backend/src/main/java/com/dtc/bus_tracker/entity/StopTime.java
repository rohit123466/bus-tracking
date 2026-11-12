package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalTime;

@Entity
// A trip's stops are always read in order (findByTrip_IdOrderByStopSequenceAsc); with this index
// that is one range scan that already has the departure time, instead of a filter plus a sort.
@Table(name = "stop_times", indexes = @Index(name = "idx_stop_times_route_trip", columnList = "trip_id, stop_sequence, departure_time"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StopTime {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stop_id", nullable = false)
    private Stop stop;

    @Column(name = "arrival_time", nullable = false)
    private LocalTime arrivalTime;

    @Column(name = "departure_time", nullable = false)
    private LocalTime departureTime;

    @Column(name = "stop_sequence", nullable = false)
    private Integer stopSequence;
}