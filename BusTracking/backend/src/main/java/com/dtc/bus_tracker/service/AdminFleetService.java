package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.FleetBusResponse;
import com.dtc.bus_tracker.dto.FleetPageResponse;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.repository.BusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Paginated fleet list for the admin console. The bus registry and the live
 * location store are both small enough (a few thousand buses for the covered
 * area) to join, filter and sort in memory on each request.
 */
@Service
@RequiredArgsConstructor
public class AdminFleetService {

    public static final int MAX_SIZE = 500;

    private static final Map<String, Function<FleetBusResponse, Comparable>> SORT_FIELDS = Map.of(
            "vehicleId", FleetBusResponse::getVehicleId,
            "route", r -> r.getRouteName() != null ? r.getRouteName() : r.getRouteCode(),
            "lastSeen", FleetBusResponse::getLastSeen,
            "speed", FleetBusResponse::getSpeedKmh,
            "status", FleetBusResponse::getLiveStatus);

    private final BusRepository busRepository;
    private final BusLocationStore busLocationStore;

    /**
     * @param q      case-insensitive substring of vehicle ID, registration, route code or route number
     * @param status comma-separated {@link BusLiveStatus} names; blank for all
     * @param route  exact route code or route number (case-insensitive); blank for all
     * @param sort   "field" or "field,asc|desc" with field one of vehicleId, route, lastSeen, speed, status
     */
    public FleetPageResponse list(String q, String status, String route, String sort, int page, int size) {
        Set<BusLiveStatus> statuses = parseStatuses(status);
        Comparator<FleetBusResponse> order = parseSort(sort);
        if (page < 0) throw badRequest("page must be 0 or more.");
        if (size < 1 || size > MAX_SIZE) throw badRequest("size must be between 1 and " + MAX_SIZE + ".");

        long now = System.currentTimeMillis() / 1000;
        String needle = blank(q) ? null : q.trim().toLowerCase(Locale.ROOT);
        String routeFilter = blank(route) ? null : route.trim();

        Map<BusLiveStatus, Long> counts = new EnumMap<>(BusLiveStatus.class);
        for (BusLiveStatus s : BusLiveStatus.values()) counts.put(s, 0L);
        List<FleetBusResponse> matching = new ArrayList<>();
        for (FleetBusResponse row : rows(now)) {
            if (needle != null && !matchesQuery(row, needle)) continue;
            if (routeFilter != null && !routeFilter.equalsIgnoreCase(row.getRouteCode())
                    && !routeFilter.equalsIgnoreCase(row.getRouteName())) continue;
            counts.merge(row.getLiveStatus(), 1L, Long::sum);
            if (statuses.contains(row.getLiveStatus())) matching.add(row);
        }
        matching.sort(order);

        int from = (int) Math.min((long) page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        return FleetPageResponse.builder()
                .content(List.copyOf(matching.subList(from, to)))
                .page(page)
                .size(size)
                .totalElements(matching.size())
                .totalPages((matching.size() + size - 1) / size)
                .statusCounts(counts)
                .generatedAt(now)
                .build();
    }

    /** Every registered bus, plus any vehicle in the live store whose registry row is missing. */
    private List<FleetBusResponse> rows(long now) {
        Map<String, BusLocationEvent> latest = new HashMap<>();
        for (BusLocationEvent e : busLocationStore.findAll()) {
            if (e.getVehicleId() != null) latest.put(e.getVehicleId(), e);
        }
        List<FleetBusResponse> rows = new ArrayList<>();
        for (Bus bus : busRepository.findAllWithRoute()) {
            rows.add(row(bus, latest.remove(bus.getVehicleId()), now));
        }
        for (BusLocationEvent orphan : latest.values()) {
            rows.add(row(null, orphan, now));
        }
        return rows;
    }

    private static FleetBusResponse row(Bus bus, BusLocationEvent event, long now) {
        var b = FleetBusResponse.builder().liveStatus(BusLiveStatus.of(event, now));
        if (bus != null) {
            b.vehicleId(bus.getVehicleId())
                    .registrationNumber(bus.getRegistrationNumber())
                    .busType(bus.getBusType())
                    .status(bus.getStatus())
                    .wheelchairAccessible(bus.getWheelchairAccessible())
                    .cameraInstalled(bus.getCameraInstalled());
            if (bus.getRoute() != null) {
                b.routeCode(bus.getRoute().getRouteCode()).routeName(bus.getRoute().getName());
            }
        }
        if (event != null) {
            b.vehicleId(event.getVehicleId())
                    .lastSeen(event.getTimestamp())
                    .speedKmh(event.getSpeedKmh())
                    .bearing(event.getBearing())
                    .latitude(event.getLatitude())
                    .longitude(event.getLongitude())
                    .wheelchairSpaceAvailable(event.getWheelchairSpaceAvailable());
            // The live event carries the route the bus reports now; the registry may lag behind it.
            boolean registryAgrees = bus != null && bus.getRoute() != null
                    && bus.getRoute().getRouteCode() != null
                    && bus.getRoute().getRouteCode().equals(event.getRouteId());
            if (event.getRouteId() != null && !registryAgrees) b.routeCode(event.getRouteId()).routeName(null);
        }
        return b.build();
    }

    private static boolean matchesQuery(FleetBusResponse row, String needle) {
        return contains(row.getVehicleId(), needle) || contains(row.getRegistrationNumber(), needle)
                || contains(row.getRouteCode(), needle) || contains(row.getRouteName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    static Set<BusLiveStatus> parseStatuses(String status) {
        if (blank(status)) return EnumSet.allOf(BusLiveStatus.class);
        Set<BusLiveStatus> out = EnumSet.noneOf(BusLiveStatus.class);
        for (String part : status.split(",")) {
            if (part.isBlank()) continue;
            try {
                out.add(BusLiveStatus.valueOf(part.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw badRequest("Unknown status '" + part.trim() + "'. Use one of "
                        + Arrays.toString(BusLiveStatus.values()) + ".");
            }
        }
        return out.isEmpty() ? EnumSet.allOf(BusLiveStatus.class) : out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Comparator<FleetBusResponse> parseSort(String sort) {
        String[] parts = blank(sort) ? new String[] {"lastSeen", "desc"} : sort.trim().split(",");
        String field = parts[0].trim();
        Function<FleetBusResponse, Comparable> getter = SORT_FIELDS.get(field);
        if (getter == null) throw badRequest("Unknown sort '" + field + "'. Use one of " + SORT_FIELDS.keySet() + ".");
        String dir = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
        if (!dir.equals("asc") && !dir.equals("desc")) throw badRequest("Sort direction must be asc or desc.");
        // Rows without a value go last in either direction.
        Comparator<Comparable> values = dir.equals("desc") ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<FleetBusResponse> primary = Comparator.comparing(getter, Comparator.nullsLast(values));
        // Vehicle ID breaks ties so pages do not shuffle between requests.
        return primary.thenComparing(FleetBusResponse::getVehicleId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
