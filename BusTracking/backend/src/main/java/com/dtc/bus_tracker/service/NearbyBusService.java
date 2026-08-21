package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.NearbyBusResponse;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NearbyBusService {

    private final StopRepository stopRepository;
    private final BusLocationStore busLocationStore;

    public List<NearbyBusResponse> findNearbyBuses(double lat, double lng, double radiusMeters, int limit) {
        List<Stop> allStops = stopRepository.findAll();
        Stop nearestStop = null;
        double minStopDist = Double.MAX_VALUE;
        for (Stop stop : allStops) {
            double dist = GeoUtils.haversine(lat, lng, stop.getLatitude(), stop.getLongitude());
            if (dist < minStopDist) {
                minStopDist = dist;
                nearestStop = stop;
            }
        }
        
        String contextStopName = nearestStop != null ? nearestStop.getName() : "Unknown Stop";
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
                responses.add(NearbyBusResponse.builder()
                        .vehicleId(bus.getVehicleId())
                        .routeCode(bus.getRouteId())
                        .stopName(contextStopName)
                        .busLatitude(bus.getLatitude())
                        .busLongitude(bus.getLongitude())
                        .distanceToStop(minStopDist)
                        .distanceToUser(distanceToUser)
                        .etaMinutes(etaMinutes)
                        .wheelchairSpaceAvailable(bus.getWheelchairSpaceAvailable())
                        .build());
            }
        }

        responses.sort((r1, r2) -> Double.compare(r1.getDistanceToUser(), r2.getDistanceToUser()));
        return responses.stream().limit(limit).toList();
    }
}