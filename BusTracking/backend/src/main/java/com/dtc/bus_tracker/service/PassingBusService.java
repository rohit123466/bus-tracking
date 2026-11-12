package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.PassingBusResponse;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.util.EtaCalculator;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Stop positions and route accessibility from memory instead of a query per stop and bus. */
    private final GtfsLookupCache lookups;

    /** No per-vehicle seat data in GTFS - fixed count reflecting DTC's standard low-floor fit-out. */
    @Value("${dtc.accessibility.wheelchair-spaces:2}")
    private int wheelchairSpaces;

    public List<PassingBusResponse> findBusesPassingNear(double lat, double lng, double radiusMeters, int limit) {
        List<Long> nearbyIds = lookups.stops().stream()
                .filter(stop -> GeoUtils.haversine(lat, lng, stop.latitude(), stop.longitude()) <= radiusMeters)
                .map(GtfsLookupCache.StopPoint::id)
                .toList();
        // Only the stops in range are loaded, together with their routes.
        List<Stop> nearbyStops = nearbyIds.isEmpty() ? List.of() : stopRepository.findWithRoutesByIdIn(nearbyIds);
        // A route's destination is the same for each of its buses and stops: looked up once.
        Map<Long, String> destinations = new HashMap<>();

        Collection<BusLocationEvent> liveBuses = busLocationStore.findAll();
        List<PassingBusResponse> results = new ArrayList<>();

        for (Stop stop : nearbyStops) {
            for (Route route : stop.getRoutes()) {
                for (BusLocationEvent bus : liveBuses) {
                    if (!route.getRouteCode().equals(bus.getRouteId())) continue;

                    double distanceToStop = GeoUtils.haversine(
                            stop.getLatitude(), stop.getLongitude(), bus.getLatitude(), bus.getLongitude());

                    if (!destinations.containsKey(route.getId())) {
                        List<Stop> orderedStops = routeStopSequenceService.orderedStops(route);
                        destinations.put(route.getId(),
                                orderedStops.isEmpty() ? null : orderedStops.get(orderedStops.size() - 1).getName());
                    }
                    String destination = destinations.get(route.getId());
                    boolean accessible = lookups.wheelchairAccessible(route.getId());

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
                            .wheelchairSpaceAvailable(bus.getWheelchairSpaceAvailable())
                            .build());
                }
            }
        }

        results.sort(Comparator.comparing(PassingBusResponse::getEtaMinutes, Comparator.nullsLast(Integer::compareTo)));
        return results.stream().limit(limit).toList();
    }
}
