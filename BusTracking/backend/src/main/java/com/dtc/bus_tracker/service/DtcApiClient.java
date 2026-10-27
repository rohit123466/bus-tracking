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

    @Value("${dtc.api.url:https://otd.delhi.gov.in/api/realtime/VehiclePositions.pb}")
    private String apiUrl;

    @Value("${dtc.api.key:}")
    private String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();

    /**
     * ETag of the last feed accepted. The OTD feed is a static file that changes about every 11 s;
     * with If-None-Match an unchanged poll is a body-less 304, so the feed can be polled often
     * (new positions land sooner) without re-downloading, re-parsing or re-publishing anything.
     */
    private String feedEtag;

    /**
     * Fresh vehicle positions from a new feed, or an empty list when the feed is unchanged since
     * the last call (nothing new to publish).
     */
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

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(12))
                .GET();
        if (feedEtag != null) builder.header("If-None-Match", feedEtag);

        HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 304) {
            snapshot.unchanged();
            return List.of();
        }
        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            throw new FeedResponseException("DTC API returned HTTP " + response.statusCode() + (response.statusCode() == 401 || response.statusCode() == 403 ? "; check DTC_API_KEY authorization." : "; live positions unavailable."));
        }

        FeedMessage feed = FeedMessage.parseFrom(response.body());
        snapshot.accept(feed, true, false);
        feedEtag = response.headers().firstValue("ETag").orElse(null);
        if (!tripUpdatesUrl.isBlank()) {
            try {
                var updateRequest = HttpRequest.newBuilder(URI.create(tripUpdatesUrl)).timeout(java.time.Duration.ofSeconds(12)).GET().build();
                var updates = httpClient.send(updateRequest, HttpResponse.BodyHandlers.ofByteArray());
                if (updates.statusCode() == 200) snapshot.accept(FeedMessage.parseFrom(updates.body()), false, true);
            } catch (Exception ignored) { /* existing predictions expire after 120s */ }
        }
        List<BusLocationEvent> events = new ArrayList<>();
        for (var v : snapshot.vehicles().values()) {
            if (!com.dtc.bus_tracker.live.RealtimeSnapshot.fresh(v.timestamp())) continue;
            events.add(BusLocationEvent.builder()
                    .vehicleId(v.id()).latitude(v.lat()).longitude(v.lng())
                    .routeId(v.routeId()).tripId(v.tripId())
                    .timestamp(v.timestamp())
                    .speedKmh(v.speedKmh()).bearing(v.bearing())
                    .wheelchairSpaceAvailable(null).build());
        }
        return events;
    }
}