package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory copies of the static GTFS lookups that the polled "near me" endpoints repeated on
 * every request: every stop's position (a full table read per request) and whether a route has
 * a wheelchair-accessible trip (two queries per live bus). The data only changes when GTFS is
 * re-imported, so answers are reused for {@link #TTL_MS}; an import in progress is picked up
 * within that time.
 */
@Component
@RequiredArgsConstructor
public class GtfsLookupCache {
    static final long TTL_MS = 60_000;

    /** A stop's database id, name and position - all a distance check needs. */
    public record StopPoint(Long id, String name, double latitude, double longitude) {}

    private record Timed<T>(T value, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < TTL_MS;
        }
    }

    /** Accessibility is tri-state for a route code: unknown route -> null. */
    private record Accessible(Boolean value) {}

    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;

    private volatile Timed<List<StopPoint>> stops;
    private final Map<String, Timed<Accessible>> byRouteCode = new ConcurrentHashMap<>();
    private final Map<Long, Timed<Boolean>> byRouteId = new ConcurrentHashMap<>();

    /** Every stop, in id order. */
    public List<StopPoint> stops() {
        var cached = stops;
        if (cached != null && cached.fresh()) return cached.value();
        List<StopPoint> loaded = new ArrayList<>();
        for (Object[] row : stopRepository.findAllPoints())
            loaded.add(new StopPoint((Long) row[0], (String) row[1], (Double) row[2], (Double) row[3]));
        stops = new Timed<>(List.copyOf(loaded), System.currentTimeMillis());
        return stops.value();
    }

    /** Whether any trip of the route with this code is wheelchair-accessible; null for an unknown code. */
    public Boolean wheelchairAccessible(String routeCode) {
        if (routeCode == null) return null;
        var cached = byRouteCode.get(routeCode);
        if (cached != null && cached.fresh()) return cached.value().value();
        Boolean value = routeRepository.findByRouteCode(routeCode)
                .map(route -> tripRepository.existsByRoute_IdAndWheelchairAccessibleTrue(route.getId()))
                .orElse(null);
        byRouteCode.put(routeCode, new Timed<>(new Accessible(value), System.currentTimeMillis()));
        return value;
    }

    /** Whether any trip of this route is wheelchair-accessible. */
    public boolean wheelchairAccessible(Long routeId) {
        var cached = byRouteId.get(routeId);
        if (cached != null && cached.fresh()) return cached.value();
        boolean value = tripRepository.existsByRoute_IdAndWheelchairAccessibleTrue(routeId);
        byRouteId.put(routeId, new Timed<>(value, System.currentTimeMillis()));
        return value;
    }
}
