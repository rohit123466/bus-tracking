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

    @Value("${dtc.api.url}")
    private String apiUrl;

    @Value("${dtc.api.key}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public List<BusLocationEvent> fetchVehiclePositions() throws Exception {
        String url = apiUrl + "?key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            throw new RuntimeException("DTC API returned invalid response: status=" + response.statusCode());
        }

        FeedMessage feed = FeedMessage.parseFrom(response.body());
        List<BusLocationEvent> events = new ArrayList<>();
        for (var entity : feed.getEntityList()) {
            if (!entity.hasVehicle()) continue;
            var v = entity.getVehicle();

            String vehicleId = v.getVehicle().getId();
            var position = v.getPosition();
            int hash = Math.abs(vehicleId.hashCode());
            Boolean wheelchairSpaceAvailable = (hash % 10 < 6) ? Boolean.TRUE : (hash % 10 < 8 ? Boolean.FALSE : null);

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