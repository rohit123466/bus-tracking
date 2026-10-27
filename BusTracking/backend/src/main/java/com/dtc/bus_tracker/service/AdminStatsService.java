package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.AdminStatsResponse;
import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.RouteStat;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Live/offline/delayed are all derived from the same in-memory location feed
 * rather than a stored schedule, since this project has no per-trip
 * schedule-adherence tracking. "Delayed" here means "reporting a live
 * position but moving well below normal traffic speed" - a proxy for being
 * stuck, not a comparison against a timetable. Thresholds live in
 * {@link BusLiveStatus}.
 */
@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final BusRepository busRepository;
    private final BusLocationStore busLocationStore;
    private final RouteRepository routeRepository;

    public AdminStatsResponse getStats() {
        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        Collection<BusLocationEvent> allLive = busLocationStore.findAll();

        List<BusLocationEvent> reporting = allLive.stream()
                .filter(e -> BusLiveStatus.isReporting(e, nowEpochSeconds))
                .toList();

        long totalRegisteredBuses = busRepository.count();
        long liveCount = reporting.size();
        long offlineCount = Math.max(0, totalRegisteredBuses - liveCount);

        long delayedCount = reporting.stream()
                .filter(e -> BusLiveStatus.of(e, nowEpochSeconds) == BusLiveStatus.SLOW)
                .count();

        double averageSpeed = reporting.stream()
                .filter(e -> e.getSpeedKmh() != null)
                .mapToDouble(e -> e.getSpeedKmh())
                .average()
                .orElse(0.0);

        Map<String, Long> busesPerRoute = reporting.stream()
                .filter(e -> e.getRouteId() != null)
                .collect(Collectors.groupingBy(e -> e.getRouteId(), Collectors.counting()));

        // routeCode is the GTFS route_id; the public route number is the route's name.
        Map<String, String> routeNames = busesPerRoute.isEmpty() ? Map.of()
                : routeRepository.findByRouteCodeIn(busesPerRoute.keySet()).stream()
                        .filter(r -> r.getName() != null && !r.getName().isBlank())
                        .collect(Collectors.toMap(Route::getRouteCode, Route::getName, (a, b) -> a));

        List<RouteStat> routeStats = busesPerRoute.entrySet().stream()
                .map(e -> RouteStat.builder()
                        .routeCode(e.getKey())
                        .routeName(routeNames.get(e.getKey()))
                        .activeBusCount(e.getValue())
                        .build())
                .sorted(Comparator.comparingLong((RouteStat r) -> r.getActiveBusCount()).reversed())
                .toList();

        return AdminStatsResponse.builder()
                .liveBusCount(liveCount)
                .offlineBusCount(offlineCount)
                .delayedBusCount(delayedCount)
                .averageSpeedKmh(Math.round(averageSpeed * 10) / 10.0)
                .routeStats(routeStats)
                .build();
    }
}
