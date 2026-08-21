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

    public void ingest(BusLocationEvent event) {
        if (event.getSpeedKmh() == null || event.getSpeedKmh() == 0.0) {
            busLocationStore.findByVehicleId(event.getVehicleId())
                    .ifPresent(previous -> deriveSpeedFromMovement(previous, event));
        }

        busLocationStore.save(event);
        Bus bus = upsertBus(event);
        recordHistory(bus, event);
        messagingTemplate.convertAndSend(BUS_TOPIC, event);
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

    private Bus upsertBus(BusLocationEvent event) {
        Bus bus = busRepository.findByVehicleId(event.getVehicleId())
                .orElseGet(() -> Bus.builder().vehicleId(event.getVehicleId()).build());

        bus.setStatus(BusStatus.ACTIVE);
        if (event.getWheelchairSpaceAvailable() != null) {
            bus.setWheelchairAccessible(event.getWheelchairSpaceAvailable());
        } else if (bus.getWheelchairAccessible() != null) {
            event.setWheelchairSpaceAvailable(bus.getWheelchairAccessible());
        }

        if (event.getRouteId() != null) {
            Route route = routeRepository.findByRouteCode(event.getRouteId()).orElse(null);
            if (route != null) {
                bus.setRoute(route);
            }
        }
        return busRepository.save(bus);
    }

    private void recordHistory(Bus bus, BusLocationEvent event) {
        LocalDateTime recordedAt = event.getTimestamp() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(event.getTimestamp()), ZoneId.systemDefault())
                : LocalDateTime.now();

        busLocationRepository.save(BusLocation.builder()
                .bus(bus)
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .speed(event.getSpeedKmh())
                .recordedAt(recordedAt)
                .build());
    }
}
