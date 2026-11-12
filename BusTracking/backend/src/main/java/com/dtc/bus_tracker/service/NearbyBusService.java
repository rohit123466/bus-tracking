package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.NearbyBusResponse;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NearbyBusService {

    private final BusLocationStore busLocationStore;
    /** Stop positions and route accessibility from memory: nothing here reads the database per bus. */
    private final GtfsLookupCache lookups;

    /** No per-vehicle seat data in GTFS - fixed count reflecting DTC's standard low-floor fit-out. */
    @Value("${dtc.accessibility.wheelchair-spaces:2}")
    private int wheelchairSpaces;

    public List<NearbyBusResponse> findNearbyBuses(double lat, double lng, double radiusMeters, int limit) {
        GtfsLookupCache.StopPoint nearestStop = null;
        double minStopDist = Double.MAX_VALUE;
        for (GtfsLookupCache.StopPoint stop : lookups.stops()) {
            double dist = GeoUtils.haversine(lat, lng, stop.latitude(), stop.longitude());
            if (dist < minStopDist) {
                minStopDist = dist;
                nearestStop = stop;
            }
        }
        
        String contextStopName = nearestStop != null ? nearestStop.name() : "Unknown Stop";
        Collection<BusLocationEvent> buses = busLocationStore.findAll();

        if (buses.isEmpty() && nearestStop != null && minStopDist <= radiusMeters) {
            return List.of(NearbyBusResponse.builder()
                    .stopName(contextStopName)
                    .distanceToStop(minStopDist)
                    .build());
        }

        List<NearbyBusResponse> responses = new ArrayList<>();
        for (BusLocationEvent bus : buses) {
            double distanceToUser = GeoUtils.haversine(lat, lng, bus.getLatitude(), bus.getLongitude());
            
            if (distanceToUser <= radiusMeters) {
                int etaMinutes = (int) Math.ceil(distanceToUser / 333.0);
                Boolean accessible = lookups.wheelchairAccessible(bus.getRouteId());
                responses.add(NearbyBusResponse.builder()
                        .vehicleId(bus.getVehicleId())
                        .routeCode(bus.getRouteId())
                        .stopName(contextStopName)
                        .busLatitude(bus.getLatitude())
                        .busLongitude(bus.getLongitude())
                        .distanceToStop(minStopDist)
                        .distanceToUser(distanceToUser)
                        .etaMinutes(etaMinutes)
                        .wheelchairAccessible(accessible)
                        .wheelchairSpaces(Boolean.TRUE.equals(accessible) ? wheelchairSpaces : 0)
                        .wheelchairSpaceAvailable(bus.getWheelchairSpaceAvailable())
                        .build());
            }
        }

        responses.sort((r1, r2) -> Double.compare(r1.getDistanceToUser(), r2.getDistanceToUser()));
        return responses.stream().limit(limit).toList();
    }
}