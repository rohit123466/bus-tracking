package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.SeatType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Camera-observed occupancy next to ticket-based occupancy for one bus.
 * The two are reported separately on purpose; {@link #mismatches} lists
 * where they disagree.
 */
@Getter
@Builder
public class CameraReport {
    private String busNumber;
    private boolean cameraInstalled;
    /** CONNECTED, DISCONNECTED or NOT_INSTALLED. */
    private String cameraStatus;
    private String provider;
    /** True while observations come from the simulator rather than a real vision model. */
    private boolean simulated;
    private String disclaimer;
    private int scanNumber;
    private LocalDateTime lastScanAt;
    private String scanTakenAtStop;
    private String currentStop;
    /** True when the bus has moved on since the last scan. */
    private boolean scanStale;
    private int cameraOccupied;
    private int cameraEmpty;
    private int ticketOccupied;
    private List<SeatObservation> observations;
    private List<Mismatch> mismatches;

    @Getter
    @Builder
    public static class SeatObservation {
        private String seatNumber;
        private SeatType seatType;
        private CameraSeatStatus cameraStatus;
        private double confidence;
        private String source;
        private LocalDateTime detectedAt;
        /** OCCUPIED/EMPTY according to tickets at the bus's current stop. */
        private CameraSeatStatus ticketStatus;
        private String ticketNumber;
        private boolean mismatch;
    }

    @Getter
    @Builder
    public static class Mismatch {
        private String seatNumber;
        /** TICKETED_BUT_EMPTY or OCCUPIED_WITHOUT_TICKET. */
        private String type;
        private String message;
    }
}
