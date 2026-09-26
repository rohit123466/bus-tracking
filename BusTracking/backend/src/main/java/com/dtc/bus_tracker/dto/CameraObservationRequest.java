package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.CameraSeatStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Seat observations pushed into the camera module - by a conductor
 * correcting the view manually today, or by a real computer-vision service
 * later. Seats not listed keep their value from the previous scan.
 */
@Getter
@Setter
public class CameraObservationRequest {

    @NotEmpty
    @Valid
    private List<Observation> observations;

    /** e.g. "MANUAL" (default) or the reporting CV system's name. */
    @Size(max = 40)
    private String source;

    @Getter
    @Setter
    public static class Observation {
        @NotBlank
        private String seatNumber;
        @NotNull
        private CameraSeatStatus status;
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private Double confidence;
    }
}
