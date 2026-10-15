package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.live.TransitIndex;
import com.dtc.bus_tracker.repository.RouteRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Polls the real Delhi OTD live vehicle-position feed and publishes it.
 *
 * The raw feed reports thousands of vehicles across all of Delhi per poll,
 * which would overwhelm the demo profile's file-based H2 store. Since
 * {@link GtfsImportService} already restricts the imported routes to the
 * North/North West Delhi bounding box, we reuse that same route set here to
 * drop every vehicle whose route isn't one we actually track - shrinking the
 * feed to just the area this deployment covers, in every profile.
 *
 * A secondary cross-check against the GTFS static TransitIndex ensures that
 * a vehicle's feed-reported route_id is consistent with the route_id that
 * trips.txt assigns to its trip_id. This rejects vehicles where the
 * TripDescriptor.route_id in the realtime feed conflicts with the authoritative
 * static GTFS mapping — matching the guard already present in LiveTransitService.
 */
@Service
public class DtcIngestionService {

    private final DtcApiClient dtcApiClient;
    private final BusLocationPublisher publisher;
    private final RouteRepository routeRepository;
    /** GTFS static index — used for trip_id → route_id cross-validation. */
    private final TransitIndex transitIndex;
    private Set<String> routeCodes = Set.of();
    private long mappingsLoadedAt;

    public DtcIngestionService(DtcApiClient dtcApiClient, BusLocationPublisher publisher,
                               RouteRepository routeRepository, TransitIndex transitIndex) {
        this.dtcApiClient = dtcApiClient;
        this.publisher = publisher;
        this.routeRepository = routeRepository;
        this.transitIndex = transitIndex;
    }

    @Scheduled(fixedDelayString = "${dtc.poll.interval:10000}")
    public void pollAndPublish() {
        try {
            if (routeCodes.isEmpty() || System.currentTimeMillis() - mappingsLoadedAt > 300000) {
                routeCodes = routeRepository.findAll().stream()
                        .map(r -> r.getRouteCode())
                        .collect(Collectors.toSet());
                mappingsLoadedAt = System.currentTimeMillis();
            }
            // Fetch the full feed even while the legacy regional database is being seeded.
            List<BusLocationEvent> events = dtcApiClient.fetchVehiclePositions().stream()
                    .filter(e -> routeCodes.contains(e.getRouteId()))
                    // Cross-check: if this vehicle has a trip_id, verify that the trip's
                    // authoritative GTFS route_id matches the feed-reported route_id.
                    // Vehicles where these two disagree are rejected to avoid publishing
                    // a vehicle on the wrong route (matches the check in LiveTransitService).
                    .filter(e -> isTripRouteConsistent(e))
                    .toList();
            events.forEach(publisher::publish);
            System.out.println("Published " + events.size() + " bus location events (North/North West Delhi routes)");
        } catch (Exception e) {
            System.err.println("DTC ingestion failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Returns true when the event's trip_id → route_id mapping in the GTFS
     * static index is consistent with the feed-reported route_id, OR when the
     * trip_id is blank/unknown (no static mapping available to contradict it).
     * Always returns false when the two route_ids explicitly conflict.
     */
    private boolean isTripRouteConsistent(BusLocationEvent event) {
        String tripId   = event.getTripId();
        String routeId  = event.getRouteId();
        if (tripId == null || tripId.isBlank() || routeId == null || routeId.isBlank()) {
            // No trip_id in the feed — we cannot cross-check, so allow through.
            return true;
        }
        var trip = transitIndex.trips.get(tripId);
        if (trip == null) {
            // Trip not in our GTFS static data (possibly outside bounding box or new service).
            // Allow through so we don't silently drop valid vehicles.
            return true;
        }
        // Reject when the static GTFS disagrees with the realtime feed.
        boolean consistent = routeId.equals(trip.routeId());
        if (!consistent) {
            System.out.println("[DtcIngestion] Rejected vehicle " + event.getVehicleId()
                    + ": feed route_id=" + routeId
                    + " conflicts with GTFS trip " + tripId
                    + " → route " + trip.routeId());
        }
        return consistent;
    }
}