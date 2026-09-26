package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "passengers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Passenger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Registered category; a ticket's category must match it (e.g. a verified disability ID). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PassengerCategory category;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
