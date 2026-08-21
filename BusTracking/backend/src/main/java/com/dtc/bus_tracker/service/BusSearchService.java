package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusDetailResponse;
import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.RouteDetailResponse;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.exception.ResourceNotFoundException;
import com.dtc.bus_tracker.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusSearchService {

    private final BusLocationStore busLocationStore;
    private final RouteRepository routeRepository;
    private final RouteDetailService routeDetailService;

    public BusDetailResponse findByVehicleId(String vehicleId) {
        BusLocationEvent event = busLocationStore.findByVehicleId(vehicleId)
                .orElseThrow(() -> new ResourceNotFoundException("No live location for bus " + vehicleId));

        Route route = event.getRouteId() != null
                ? routeRepository.findByRouteCode(event.getRouteId()).orElse(null)
                : null;

        RouteDetailResponse routeDetail = route != null
                ? routeDetailService.buildDetail(route, vehicleId)
                : null;

        return BusDetailResponse.builder()
                .vehicleId(vehicleId)
                .routeCode(event.getRouteId())
                .routeName(route != null ? route.getName() : null)
                .latitude(event.getLatitude())
                .longitude(event.getLongitude())
                .speedKmh(event.getSpeedKmh())
                .bearing(event.getBearing())
                .lastUpdatedEpochSeconds(event.getTimestamp())
                .wheelchairAccessible(routeDetail != null ? routeDetail.getWheelchairAccessible() : null)
                .wheelchairSpaces(routeDetail != null ? routeDetail.getWheelchairSpaces() : null)
                .route(routeDetail)
                .build();
    }
}
