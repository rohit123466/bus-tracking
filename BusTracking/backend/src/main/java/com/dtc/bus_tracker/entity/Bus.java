package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "buses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Bus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vehicle_id", unique = true, nullable = false)
    private String vehicleId;

    @Column(name = "registration_number")
    private String registrationNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    private Route route;

    @Enumerated(EnumType.STRING)
    private BusStatus status;

    @Column(name = "wheelchair_accessible")
    private Boolean wheelchairAccessible;
}