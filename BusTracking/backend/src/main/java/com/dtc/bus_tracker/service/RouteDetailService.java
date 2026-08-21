package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.RouteDetailResponse;
import com.dtc.bus_tracker.dto.RouteStopInfo;
import com.dtc.bus_tracker.dto.StopProgress;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.exception.ResourceNotFoundException;
import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import com.dtc.bus_tracker.util.EtaCalculator;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RouteDetailService {

    private final RouteRepository routeRepository;
    private final RouteStopSequenceService routeStopSequenceService;
    private final BusLocationStore busLocationStore;
    private final TripRepository tripRepository;

    /** No per-vehicle seat data in GTFS - fixed count reflecting DTC's standard low-floor fit-out. */
    @Value("${dtc.accessibility.wheelchair-spaces:2}")
    private int wheelchairSpaces;

    public RouteDetailResponse getDetail(Long routeId, String vehicleId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found: " + routeId));
        return buildDetail(route, vehicleId);
    }

    public RouteDetailResponse buildDetail(Route route, String vehicleId) {
        List<Stop> stops = routeStopSequenceService.orderedStops(route);
        BusLocationEvent liveEvent = vehicleId != null
                ? busLocationStore.findByVehicleId(vehicleId).orElse(null)
                : null;

        int currentIndex = (liveEvent != null && !stops.isEmpty())
                ? nearestStopIndex(stops, liveEvent.getLatitude(), liveEvent.getLongitude())
                : -1;

        List<RouteStopInfo> infos = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            infos.add(RouteStopInfo.builder()
                    .stopId(stop.getId())
                    .stopCode(stop.getStopId())
                    .name(stop.getName())
                    .latitude(stop.getLatitude())
                    .longitude(stop.getLongitude())
                    .sequence(i + 1)
                    .progress(currentIndex < 0 ? null : progressFor(i, currentIndex))
                    .wheelchairBoarding(stop.getWheelchairBoarding())
                    .build());
        }

        Integer etaToNextStopMinutes = null;
        if (liveEvent != null && currentIndex >= 0 && currentIndex + 1 < stops.size()) {
            Stop next = stops.get(currentIndex + 1);
            double distance = GeoUtils.haversine(
                    liveEvent.getLatitude(), liveEvent.getLongitude(),
                    next.getLatitude(), next.getLongitude());
            etaToNextStopMinutes = EtaCalculator.estimateMinutes(distance, liveEvent.getSpeedKmh());
        }

        boolean wheelchairAccessible = tripRepository.existsByRoute_IdAndWheelchairAccessibleTrue(route.getId());

        return RouteDetailResponse.builder()
                .id(route.getId())
                .routeCode(route.getRouteCode())
                .name(route.getName())
                .wheelchairAccessible(wheelchairAccessible)
                .wheelchairSpaces(wheelchairAccessible ? wheelchairSpaces : 0)
                .stops(infos)
                .trackedVehicleId(liveEvent != null ? vehicleId : null)
                .vehicleLatitude(liveEvent != null ? liveEvent.getLatitude() : null)
                .vehicleLongitude(liveEvent != null ? liveEvent.getLongitude() : null)
                .etaToNextStopMinutes(etaToNextStopMinutes)
                .build();
    }

    private StopProgress progressFor(int index, int currentIndex) {
        if (index < currentIndex) return StopProgress.PASSED;
        if (index == currentIndex) return StopProgress.CURRENT;
        if (index == currentIndex + 1) return StopProgress.NEXT;
        return StopProgress.UPCOMING;
    }

    private int nearestStopIndex(List<Stop> stops, double lat, double lng) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < stops.size(); i++) {
            double distance = GeoUtils.haversine(lat, lng, stops.get(i).getLatitude(), stops.get(i).getLongitude());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
