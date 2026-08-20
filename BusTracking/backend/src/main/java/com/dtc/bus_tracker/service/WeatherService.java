package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.WeatherAlertResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Polls Open-Meteo (no API key required) for current conditions over the
 * tracked Delhi region and caches a rain/no-rain summary for riders. Also the
 * hook point for feeding a "raining" flag into ETA prediction later.
 */
@Service
public class WeatherService {

    // WMO weather codes (https://open-meteo.com/en/docs) that indicate any
    // form of rain: drizzle, rain, freezing rain, rain showers, thunderstorm.
    private static final Set<Integer> RAIN_CODES = Set.of(
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99);

    @Value("${weather.latitude:28.6139}")
    private double latitude;

    @Value("${weather.longitude:77.2090}")
    private double longitude;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<WeatherAlertResponse> cache = new AtomicReference<>();

    @Scheduled(fixedRateString = "${weather.poll.interval:600000}")
    public void refresh() {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                    + "&longitude=" + longitude
                    + "&current=precipitation,weather_code,temperature_2m&timezone=Asia%2FKolkata";

            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Open-Meteo returned status " + response.statusCode());
            }

            JsonNode current = objectMapper.readTree(response.body()).path("current");
            int weatherCode = current.path("weather_code").asInt(-1);
            double precipitationMm = current.path("precipitation").asDouble(0.0);
            boolean raining = RAIN_CODES.contains(weatherCode) || precipitationMm > 0.1;

            cache.set(WeatherAlertResponse.builder()
                    .raining(raining)
                    .condition(describe(weatherCode, raining))
                    .temperatureC(current.path("temperature_2m").asDouble())
                    .precipitationMm(precipitationMm)
                    .fetchedAt(Instant.now().toString())
                    .build());
        } catch (Exception e) {
            System.err.println("Weather refresh failed: " + e.getMessage());
        }
    }

    public WeatherAlertResponse getCurrentWeather() {
        WeatherAlertResponse cached = cache.get();
        if (cached != null) {
            return cached;
        }
        // First request can race the scheduler's initial tick; fetch inline once.
        refresh();
        WeatherAlertResponse fetched = cache.get();
        return fetched != null ? fetched : WeatherAlertResponse.builder()
                .raining(false)
                .condition("unknown")
                .fetchedAt(Instant.now().toString())
                .build();
    }

    private String describe(int weatherCode, boolean raining) {
        if (!raining) return "clear";
        if (weatherCode == 95 || weatherCode == 96 || weatherCode == 99) return "thunderstorm";
        if (weatherCode == 80 || weatherCode == 81 || weatherCode == 82) return "rain showers";
        if (weatherCode == 61 || weatherCode == 63 || weatherCode == 65 || weatherCode == 66 || weatherCode == 67) return "rain";
        return "drizzle";
    }
}
