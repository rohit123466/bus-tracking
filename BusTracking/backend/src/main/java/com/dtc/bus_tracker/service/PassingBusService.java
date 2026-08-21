package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.PassingBusResponse;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import com.dtc.bus_tracker.util.EtaCalculator;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * "Which bus will pass near me" - unlike /buses/nearby (which only returns
 * buses that are *currently* within the radius), this looks at every route
 * serving a nearby stop and reports every live bus on that route, since a
 * bus a few km away on its way to that stop is exactly what a rider waiting
 * there wants to know about.
 */
@Service
@RequiredArgsConstructor
public class PassingBusService {

    private final StopRepository stopRepository;
    private final BusLocationStore busLocationStore;
    private final RouteStopSequenceService routeStopSequenceService;
    private final TripRepository tripRepository;

    /** No per-vehicle seat data in GTFS - fixed count reflecting DTC's standard low-floor fit-out. */
    @Value("${dtc.accessibility.wheelchair-spaces:2}")
    private int wheelchairSpaces;

    public List<PassingBusResponse> findBusesPassingNear(double lat, double lng, double radiusMeters, int limit) {
        List<Stop> nearbyStops = stopRepository.findAll().stream()
                .filter(stop -> GeoUtils.haversine(lat, lng, stop.getLatitude(), stop.getLongitude()) <= radiusMeters)
                .toList();

        Collection<BusLocationEvent> liveBuses = busLocationStore.findAll();
        List<PassingBusResponse> results = new ArrayList<>();

        for (Stop stop : nearbyStops) {
            for (Route route : stop.getRoutes()) {
                for (BusLocationEvent bus : liveBuses) {
                    if (!route.getRouteCode().equals(bus.getRouteId())) continue;

                    double distanceToStop = GeoUtils.haversine(
                            stop.getLatitude(), stop.getLongitude(), bus.getLatitude(), bus.getLongitude());

                    List<Stop> orderedStops = routeStopSequenceService.orderedStops(route);
                    String destination = orderedStops.isEmpty() ? null : orderedStops.get(orderedStops.size() - 1).getName();
                    boolean accessible = tripRepository.existsByRoute_IdAndWheelchairAccessibleTrue(route.getId());

                    results.add(PassingBusResponse.builder()
                            .vehicleId(bus.getVehicleId())
                            .routeCode(route.getRouteCode())
                            .routeName(route.getName())
                            .stopName(stop.getName())
                            .destination(destination)
                            .distanceToStopMeters(distanceToStop)
                            .etaMinutes(EtaCalculator.estimateMinutes(distanceToStop, bus.getSpeedKmh()))
                            .wheelchairAccessible(accessible)
                            .wheelchairSpaces(accessible ? wheelchairSpaces : 0)
                            .build());
                }
            }
        }

        results.sort(Comparator.comparing(r -> r.getEtaMinutes()));
        return results.stream().limit(limit).toList();
    }
}
