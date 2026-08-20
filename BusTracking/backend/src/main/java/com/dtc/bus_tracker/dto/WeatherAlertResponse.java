package com.dtc.bus_tracker.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class WeatherAlertResponse {
    private boolean raining;
    private String condition;
    private Double temperatureC;
    private Double precipitationMm;
    private String fetchedAt;
}
