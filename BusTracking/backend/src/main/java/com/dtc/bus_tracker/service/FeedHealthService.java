package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.live.RealtimeSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * GTFS-realtime feed health for the admin console, read from what the existing
 * poller already records ({@link RealtimeSnapshot}, {@link DtcIngestionService}).
 * Calling it never fetches the feed.
 */
@Service
public class FeedHealthService {

    /** Same limit the rider API uses before it warns that live data is old. */
    static final long FETCH_STALE_AFTER_SECONDS = 120;

    /**
     * @param lastFetchAt       epoch seconds of the last successful poll (a 304 counts); null before the first
     * @param fetchAgeSeconds   seconds since lastFetchAt; null before the first
     * @param generation        snapshot generation, bumped whenever positions or trip updates are replaced
     * @param vehicleCount      vehicles in the current snapshot (whole feed, every route)
     * @param freshVehicleCount of those, how many have a GPS fix from the last two minutes
     * @param feedError         the current problem, or null when the feed is fine
     * @param lastError         the most recent fetch failure, kept after the feed recovers
     * @param lastErrorAt       epoch seconds of lastError; null if there has been none
     * @param lastPublishAt     epoch seconds the last batch reached the location store and /topic/buses
     * @param lastPublishCount  buses in that batch
     */
    public record FeedHealth(
            Long lastFetchAt,
            Long fetchAgeSeconds,
            long generation,
            int vehicleCount,
            int freshVehicleCount,
            String feedError,
            String lastError,
            Long lastErrorAt,
            Long lastPublishAt,
            int lastPublishCount,
            long pollIntervalMs,
            boolean healthy) {}

    private final RealtimeSnapshot snapshot;
    private final DtcIngestionService ingestion;
    private final long pollIntervalMs;

    public FeedHealthService(RealtimeSnapshot snapshot, DtcIngestionService ingestion,
                             @Value("${dtc.poll.interval:4000}") long pollIntervalMs) {
        this.snapshot = snapshot;
        this.ingestion = ingestion;
        this.pollIntervalMs = pollIntervalMs;
    }

    public FeedHealth health() {
        long now = Instant.now().getEpochSecond();
        var vehicles = snapshot.vehicles().values();
        int fresh = (int) vehicles.stream().filter(v -> RealtimeSnapshot.fresh(v.timestamp())).count();
        long fetchedAt = snapshot.fetchedAt;
        Long age = fetchedAt > 0 ? Math.max(0, now - fetchedAt) : null;
        String error = snapshot.feedError;
        long errorAt = snapshot.lastErrorAt;
        long publishedAt = ingestion.lastPublishedAt();
        return new FeedHealth(
                fetchedAt > 0 ? fetchedAt : null,
                age,
                snapshot.generation(),
                vehicles.size(),
                fresh,
                error,
                snapshot.lastError,
                errorAt > 0 ? errorAt : null,
                publishedAt > 0 ? publishedAt : null,
                ingestion.lastPublishedCount(),
                pollIntervalMs,
                error == null && age != null && age <= FETCH_STALE_AFTER_SECONDS);
    }
}
