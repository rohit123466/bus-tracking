package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.repository.StopRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fakes the DTC ingestion pipeline (DtcIngestionService -> Redis) when running
 * the "demo" profile without Redis available. Picks a handful of real
 * imported stops and walks synthetic buses around them so the frontend has
 * something to poll.
 */
@Service
@Profile("demo")
public class DemoBusSeeder {

    private static final int BUS_COUNT = 15;
    private static final double JITTER_DEGREES = 0.01; // ~1km

    private final BusLocationIngestService ingestService;
    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final Random random = new Random();

    private List<Stop> anchorStops = List.of();
    private List<Route> routes = List.of();
    private final Map<String, double[]> lastPosition = new HashMap<>(); // vehicleId -> [lat, lng]

    public DemoBusSeeder(BusLocationIngestService ingestService, StopRepository stopRepository, RouteRepository routeRepository) {
        this.ingestService = ingestService;
        this.stopRepository = stopRepository;
        this.routeRepository = routeRepository;
    }

    @Scheduled(fixedRateString = "${dtc.poll.interval:10000}", initialDelay = 3000)
    public void tick() {
        if (anchorStops.isEmpty() && !ensureAnchors()) {
            return; // GTFS import hasn't finished seeding stops yet
        }

        for (int i = 0; i < anchorStops.size(); i++) {
            Stop stop = anchorStops.get(i);
            double lat = stop.getLatitude() + (random.nextDouble() - 0.5) * JITTER_DEGREES;
            double lng = stop.getLongitude() + (random.nextDouble() - 0.5) * JITTER_DEGREES;
            String routeCode = routes.isEmpty() ? "DEMO" : routes.get(i % routes.size()).getRouteCode();
            String vehicleId = "DEMO-" + i;

            double[] prev = lastPosition.get(vehicleId);
            double bearing = prev == null ? random.nextDouble() * 360 : bearingBetween(prev[0], prev[1], lat, lng);
            lastPosition.put(vehicleId, new double[]{lat, lng});

            ingestService.ingest(BusLocationEvent.builder()
                    .vehicleId(vehicleId)
                    .latitude(lat)
                    .longitude(lng)
                    .routeId(routeCode)
                    .timestamp(System.currentTimeMillis() / 1000)
                    .speedKmh(15 + random.nextDouble() * 25)
                    .bearing(bearing)
                    .build());
        }
    }

    private double bearingBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(Math.toRadians(lat2));
        double x = Math.cos(Math.toRadians(lat1)) * Math.sin(Math.toRadians(lat2))
                - Math.sin(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private boolean ensureAnchors() {
        List<Stop> allStops = stopRepository.findAll();
        if (allStops.isEmpty()) {
            return false;
        }
        List<Stop> picked = new ArrayList<>();
        for (int i = 0; i < Math.min(BUS_COUNT, allStops.size()); i++) {
            picked.add(allStops.get(random.nextInt(allStops.size())));
        }
        anchorStops = picked;
        routes = routeRepository.findAll();
        return true;
    }
}
