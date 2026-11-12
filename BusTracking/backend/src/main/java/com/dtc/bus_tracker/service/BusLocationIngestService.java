package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.BusLocation;
import com.dtc.bus_tracker.entity.BusStatus;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.repository.BusLocationRepository;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single entry point every ingestion path (direct-to-Redis publisher, demo
 * seeder) funnels a location update through. Keeping the cache write, the
 * durable history row, and the WebSocket broadcast together here means none
 * of those call sites need to know about the other two.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusLocationIngestService {

    private static final String BUS_TOPIC = "/topic/buses";

    // The real Delhi OTD feed reports speed=0 for every vehicle regardless of
    // actual movement, so a 0/missing feed speed is derived from the distance
    // and elapsed time between this and the vehicle's last known position
    // instead of trusted as-is. Skip derivation on polls closer together than
    // this, and cap the result, so GPS jitter between near-simultaneous
    // pings can't produce spurious huge speeds.
    private static final long MIN_INTERVAL_SECONDS_FOR_SPEED = 3;
    private static final double MAX_PLAUSIBLE_SPEED_KMH = 120.0;

    private final BusLocationStore busLocationStore;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final BusLocationRepository busLocationRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final BusRegistryCache registry;

    public void ingest(BusLocationEvent event) {
        ingestAll(List.of(event));
    }

    /**
     * One feed poll. The history rows go to the database in a single transaction, and a bus row
     * is only read or written when something about the bus changed: doing all of that per event
     * was several database round trips per bus, which on a small hosted instance took longer
     * than the poll interval and starved every other request.
     */
    public void ingestAll(List<BusLocationEvent> events) {
        // Live store and WebSocket first, so the admin map is not kept waiting by the database
        // writes below (bus rows and history), which nothing on the live path depends on.
        List<BusLocationEvent> stored = new ArrayList<>(events.size());
        for (BusLocationEvent event : events) {
            try {
                if (event.getSpeedKmh() == null || event.getSpeedKmh() == 0.0) {
                    busLocationStore.findByVehicleId(event.getVehicleId())
                            .ifPresent(previous -> deriveSpeedFromMovement(previous, event));
                }
                busLocationStore.save(event);
                messagingTemplate.convertAndSend(BUS_TOPIC, event);
                stored.add(event);
            } catch (RuntimeException e) {
                log.warn("Bus location for {} skipped: {}", event.getVehicleId(), e.getMessage());
            }
        }
        if (!stored.isEmpty()) registry.liveChanged();
        List<BusLocation> history = new ArrayList<>(stored.size());
        for (BusLocationEvent event : stored) {
            try {
                history.add(historyRow(upsertBus(event), event));
            } catch (RuntimeException e) {
                log.warn("Bus row for {} not written: {}", event.getVehicleId(), e.getMessage());
            }
        }
        if (!history.isEmpty()) busLocationRepository.saveAll(history);
    }

    private void deriveSpeedFromMovement(BusLocationEvent previous, BusLocationEvent current) {
        if (previous.getTimestamp() == null || current.getTimestamp() == null) {
            return;
        }
        long elapsedSeconds = current.getTimestamp() - previous.getTimestamp();
        if (elapsedSeconds < MIN_INTERVAL_SECONDS_FOR_SPEED) {
            return;
        }

        double distanceMeters = GeoUtils.haversine(
                previous.getLatitude(), previous.getLongitude(),
                current.getLatitude(), current.getLongitude());
        double speedKmh = Math.min((distanceMeters / elapsedSeconds) * 3.6, MAX_PLAUSIBLE_SPEED_KMH);
        current.setSpeedKmh(speedKmh);
    }

    /** A bus row as last written, with the event fields it was written from. */
    private record KnownBus(Bus bus, String routeCode, Boolean wheelchair) {}

    /** vehicle id -> bus row. Cleared with the route cache, so a row edited elsewhere is picked up again. */
    private final Map<String, KnownBus> knownBuses = new ConcurrentHashMap<>();

    private Bus upsertBus(BusLocationEvent event) {
        if (System.currentTimeMillis() - routesCachedAt > ROUTE_CACHE_MS) knownBuses.clear();
        KnownBus known = knownBuses.get(event.getVehicleId());
        if (known != null && Objects.equals(known.routeCode(), event.getRouteId())
                && (event.getWheelchairSpaceAvailable() == null
                        || event.getWheelchairSpaceAvailable().equals(known.wheelchair()))) {
            return known.bus();
        }

        Bus bus = busRepository.findByVehicleId(event.getVehicleId())
                .orElseGet(() -> Bus.builder().vehicleId(event.getVehicleId()).build());

        bus.setStatus(BusStatus.ACTIVE);
        if (event.getWheelchairSpaceAvailable() != null) {
            bus.setWheelchairAccessible(event.getWheelchairSpaceAvailable());
        }

        if (event.getRouteId() != null) {
            Route route = routeByCode(event.getRouteId());
            if (route != null) {
                bus.setRoute(route);
            }
        }
        Bus saved = busRepository.save(bus);
        registry.invalidate();
        knownBuses.put(event.getVehicleId(),
                new KnownBus(saved, event.getRouteId(), saved.getWheelchairAccessible()));
        return saved;
    }

    private static final long ROUTE_CACHE_MS = 300_000;
    /** route_code -> route, so each event does not query the routes table again. */
    private final Map<String, Optional<Route>> routesByCode = new ConcurrentHashMap<>();
    private volatile long routesCachedAt;

    private Route routeByCode(String code) {
        if (System.currentTimeMillis() - routesCachedAt > ROUTE_CACHE_MS) {
            routesByCode.clear();
            routesCachedAt = System.currentTimeMillis();
        }
        return routesByCode.computeIfAbsent(code, routeRepository::findByRouteCode).orElse(null);
    }

    private BusLocation historyRow(Bus bus, BusLocationEvent event) {
        LocalDateTime recordedAt = event.getTimestamp() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(event.getTimestamp()), ZoneId.systemDefault())
                : LocalDateTime.now();

        return BusLocation.builder()
                .bus(bus)
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .speed(event.getSpeedKmh())
                .recordedAt(recordedAt)
                .build();
    }
}
