package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class DtcApiClient {

    private final com.dtc.bus_tracker.live.RealtimeSnapshot snapshot;
    public DtcApiClient(com.dtc.bus_tracker.live.RealtimeSnapshot snapshot) { this.snapshot = snapshot; }
    @Value("${dtc.trip-updates.url:}") private String tripUpdatesUrl;

    @Value("${dtc.api.url}")
    private String apiUrl;

    @Value("${dtc.api.key}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    public List<BusLocationEvent> fetchVehiclePositions() throws Exception {
        if (apiKey.isBlank() || apiKey.equals("YOUR_LOCAL_KEY")) { snapshot.feedError = "DTC_API_KEY is not configured; live positions are unavailable."; return List.of(); }
        String url = apiUrl + (apiUrl.contains("?") ? "&" : "?") + "key=" + java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(12))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            throw new RuntimeException("DTC API returned invalid response: status=" + response.statusCode());
        }

        FeedMessage feed = FeedMessage.parseFrom(response.body());
        snapshot.accept(feed, true, false);
        if (!tripUpdatesUrl.isBlank()) {
            try {
                var updateRequest = HttpRequest.newBuilder(URI.create(tripUpdatesUrl)).timeout(java.time.Duration.ofSeconds(12)).GET().build();
                var updates = httpClient.send(updateRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (updates.statusCode() == 200) snapshot.accept(FeedMessage.parseFrom(updates.body()), false, true);
            } catch (Exception ignored) { /* existing predictions expire after 120s */ }
        }
        List<BusLocationEvent> events = new ArrayList<>();
        for (var entity : feed.getEntityList()) {
            if (!entity.hasVehicle()) continue;
            var v = entity.getVehicle();

            String vehicleId = v.getVehicle().getId();
            if (vehicleId.isBlank() || !v.hasPosition()) continue;
            var position = v.getPosition();
            Boolean wheelchairSpaceAvailable = null; // Occupancy is unknown unless the feed supplies it.

            events.add(BusLocationEvent.builder()
                    .vehicleId(vehicleId)
                    .latitude((double) position.getLatitude())
                    .longitude((double) position.getLongitude())
                    .routeId(v.getTrip().getRouteId())
                    .timestamp(v.getTimestamp())
                    // GTFS-RT reports speed in m/s per spec; convert to km/h for display.
                    .speedKmh(position.hasSpeed() ? position.getSpeed() * 3.6 : null)
                    .bearing(position.hasBearing() ? (double) position.getBearing() : null)
                    .wheelchairSpaceAvailable(wheelchairSpaceAvailable)
                    .build());
        }
        return events;
    }
}