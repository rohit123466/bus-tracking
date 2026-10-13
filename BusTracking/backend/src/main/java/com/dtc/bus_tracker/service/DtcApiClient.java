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
    private final com.dtc.bus_tracker.live.LiveTransitService transit;
    public DtcApiClient(com.dtc.bus_tracker.live.RealtimeSnapshot snapshot,
            com.dtc.bus_tracker.live.LiveTransitService transit) {
        this.snapshot = snapshot;
        this.transit = transit;
    }
    @Value("${dtc.trip-updates.url:}") private String tripUpdatesUrl;

    @Value("${dtc.api.url:https://otd.delhi.gov.in/api/realtime/VehiclePositions.pb}")
    private String apiUrl;

    @Value("${dtc.api.key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    public synchronized List<BusLocationEvent> fetchVehiclePositions() throws Exception {
        try {
            return fetch();
        } catch (Exception failure) {
            // Never expose request URLs: the provider authenticates with a query parameter.
            snapshot.feedError = failure instanceof FeedResponseException ? failure.getMessage() : failure instanceof com.google.protobuf.InvalidProtocolBufferException
                    ? "DTC returned a malformed GTFS realtime feed."
                    : "DTC feed request failed (" + failure.getClass().getSimpleName() + "). Check connectivity and credentials.";
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(snapshot.feedError);
        }
    }

    private static class FeedResponseException extends RuntimeException {
        FeedResponseException(String message) { super(message); }
    }

    private List<BusLocationEvent> fetch() throws Exception {
        if (apiKey.isBlank() || apiKey.equals("YOUR_LOCAL_KEY")) { snapshot.feedError = "DTC_API_KEY is not configured; live positions are unavailable."; return List.of(); }
        String url = apiUrl + (apiUrl.contains("?") ? "&" : "?") + "key=" + java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(12))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            throw new FeedResponseException("DTC API returned HTTP " + response.statusCode() + (response.statusCode() == 401 || response.statusCode() == 403 ? "; check DTC_API_KEY authorization." : "; live positions unavailable."));
        }

        FeedMessage feed = FeedMessage.parseFrom(response.body());
        snapshot.accept(feed, true, false);
        if (!tripUpdatesUrl.isBlank()) {
            try {
                var updateRequest = HttpRequest.newBuilder(URI.create(tripUpdatesUrl)).timeout(java.time.Duration.ofSeconds(12)).GET().build();
                var updates = httpClient.send(updateRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (updates.statusCode() == 200) snapshot.accept(FeedMessage.parseFrom(updates.body()), false, true);
                else snapshot.tripUpdatesError = "TripUpdates unavailable (HTTP " + updates.statusCode() + "). Existing predictions expire after 120 seconds.";
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                snapshot.tripUpdatesError = "TripUpdates request failed; existing predictions expire after 120 seconds.";
            }
        } else {
            snapshot.tripUpdatesError = "No separate TripUpdates feed configured; arrivals require embedded updates or verified geometry and speed.";
        }
        List<BusLocationEvent> events = new ArrayList<>();
        for (var v : snapshot.vehicles().values()) {
            if (!com.dtc.bus_tracker.live.RealtimeSnapshot.fresh(v.timestamp())) continue;
            var route = transit.route(v);
            if (route == null || route.number() == null || snapshot.cancelled(v)) continue;
            events.add(BusLocationEvent.builder()
                    .vehicleId(v.id()).latitude(v.lat()).longitude(v.lng())
                    .routeId(route.id()).timestamp(v.timestamp())
                    .speedKmh(v.speedKmh()).bearing(v.bearing())
                    .wheelchairSpaceAvailable(null).build());
        }
        return events;
    }
}
