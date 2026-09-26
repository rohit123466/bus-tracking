package com.dtc.bus_tracker.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One camera observation of one seat. Rows sharing a scan_number form one
 * scan of the bus; the latest scan is the bus's current camera view. Kept
 * completely separate from tickets - the two can (and are meant to be able
 * to) disagree.
 */
@Entity
@Table(name = "camera_occupancy", indexes = {
        @Index(name = "idx_camera_bus_scan", columnList = "bus_id, scan_number")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class CameraOccupancy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bus_id", nullable = false)
    private Bus bus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(name = "scan_number", nullable = false)
    private Integer scanNumber;

    /** Trip stop sequence the bus was at when this was observed. */
    @Column(name = "stop_sequence")
    private Integer stopSequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CameraSeatStatus status;

    /** 0..1 detector confidence. */
    @Column(nullable = false)
    private Double confidence;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    /** e.g. "SIMULATED", "MANUAL", or a real CV provider's name. */
    @Column(nullable = false)
    private String source;
}
