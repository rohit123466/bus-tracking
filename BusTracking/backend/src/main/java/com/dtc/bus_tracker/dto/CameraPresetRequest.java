package com.dtc.bus_tracker.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Selects the standing-area preset played on a bus; a null id pauses the preset feed. */
@Getter
@Setter
public class CameraPresetRequest {
    @Size(max = 64)
    private String presetId;
}
