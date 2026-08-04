package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
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
 */
@Service
public class DtcIngestionService {

    private final DtcApiClient dtcApiClient;
    private final BusLocationPublisher publisher;
    private final RouteRepository routeRepository;

    public DtcIngestionService(DtcApiClient dtcApiClient, BusLocationPublisher publisher, RouteRepository routeRepository) {
        this.dtcApiClient = dtcApiClient;
        this.publisher = publisher;
        this.routeRepository = routeRepository;
    }

    @Scheduled(fixedRateString = "${dtc.poll.interval:10000}")
    public void pollAndPublish() {
        try {
            // Queried fresh each tick (cheap at this route count): a one-time
            // cache would freeze on an empty set if this poll ever ran before
            // GtfsImportService finished seeding routes at startup.
            Set<String> routeCodes = routeRepository.findAll().stream()
                    .map(r -> r.getRouteCode())
                    .collect(Collectors.toSet());
            if (routeCodes.isEmpty()) {
                return; // GTFS import hasn't finished seeding routes yet
            }

            List<BusLocationEvent> events = dtcApiClient.fetchVehiclePositions().stream()
                    .filter(e -> routeCodes.contains(e.getRouteId()))
                    .toList();
            events.forEach(publisher::publish);
            System.out.println("Published " + events.size() + " bus location events (North/North West Delhi routes)");
        } catch (Exception e) {
            System.err.println("DTC ingestion failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}