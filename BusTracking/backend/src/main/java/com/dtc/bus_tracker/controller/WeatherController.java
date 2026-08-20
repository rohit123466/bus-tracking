package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.WeatherAlertResponse;
import com.dtc.bus_tracker.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    // GET /api/weather/alert - current rain status for the tracked Delhi
    // region, polled server-side so every rider shares one cached result.
    @GetMapping("/alert")
    public ResponseEntity<WeatherAlertResponse> getWeatherAlert() {
        return ResponseEntity.ok(weatherService.getCurrentWeather());
    }
}
