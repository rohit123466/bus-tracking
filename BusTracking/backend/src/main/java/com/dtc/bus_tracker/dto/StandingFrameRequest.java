package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.service.StandingZoneTracker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

/**
 * One standing-area frame pushed by a camera / IoT device: everyone it sees
 * in the designated standing area right now. An empty list means the area is
 * clear. The device must send a stable {@code trackId} per person (so the
 * same person is not counted again in the next frame) and should send a
 * rising {@code sequence} or a {@code capturedAt} time so late or repeated
 * frames can be ignored.
 */
@Getter
@Setter
public class StandingFrameRequest {

    private Long sequence;

    private Instant capturedAt;

    /** Reporting device or system, e.g. "CAM-DTC-101-A". */
    @Size(max = 40)
    private String source;

    @NotNull
    @Size(max = 200)
    @Valid
    private List<Detection> detections;

    @Getter
    @Setter
    public static class Detection {
        @NotBlank
        @Size(max = 64)
        private String trackId;

        /** PASSENGER (default) or CONDUCTOR - staff are never counted as standing passengers. */
        private StandingZoneTracker.Role role;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private Double confidence;
    }
}
