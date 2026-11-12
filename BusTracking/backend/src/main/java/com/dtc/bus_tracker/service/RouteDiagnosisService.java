package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.BusStatus;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.live.Geometry;
import com.dtc.bus_tracker.live.RealtimeSnapshot;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.repository.StopTimeRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * "Why is nothing coming from route X?" for the admin console: splits a route's buses into
 * reporting, silent since earlier today, and not seen today, and gives each non-reporting bus
 * the most likely reason from the signals this project actually has.
 *
 * <p>There is no timetable-to-vehicle assignment, crew roster, depot list or maintenance log
 * here, so "scheduled today" means "on this route and seen today", maintenance is the bus
 * registry's status flag, and a stopped bus is placed against its route's end stops rather than
 * a depot. Checks that need missing data are listed in {@link RouteReport#notChecked()} instead
 * of being guessed. Route membership follows {@link AdminFleetService}: the route a bus last
 * reported wins over the registry's.
 */
@Service
public class RouteDiagnosisService {

    /** DTC runs in Delhi, so "today" is the IST calendar day whatever zone the server is in. */
    static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Kolkata");
    /**
     * A bus counts as silent only after this long without a fix. Live buses often go 2-3 minutes
     * between fixes, so the 2-minute "reporting" line alone would diagnose buses that are merely
     * between updates (measured 2026-11-05: 15 of 17 non-reporting buses on one route were 2-3 min old).
     */
    static final long SILENT_AFTER_SECONDS = 300;
    /** A stopped bus within this distance of its route's first or last stop is at a terminal. */
    static final double TERMINAL_RADIUS_M = 400;

    /** Most likely reason a bus is not reporting, most specific first. */
    public enum Reason {
        IN_MAINTENANCE("In maintenance"),
        WITHDRAWN("Withdrawn from service"),
        FEED_OUTAGE("Live feed outage"),
        TRIP_CANCELLED("Trip cancelled"),
        GPS_LOST_WHILE_MOVING("GPS lost while moving"),
        STOPPED_AT_TERMINAL("Stopped at a terminal"),
        STOPPED_ON_ROUTE("Stopped on the road"),
        SIGNAL_LOSS("Signal lost, cause unknown"),
        NOT_OUT_TODAY("No position today");

        public final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    /** SILENT_TODAY: reported earlier today, not in the last five minutes. NOT_SEEN_TODAY: no fix today. */
    public enum Category { SILENT_TODAY, NOT_SEEN_TODAY }

    /** One non-reporting bus. Position, speed and trip are from its last fix; all null if it has none. */
    public record BusDiagnosis(
            String vehicleId,
            String registrationNumber,
            BusStatus registryStatus,
            Category category,
            Long lastSeen,
            Double latitude,
            Double longitude,
            Double lastSpeedKmh,
            String tripId,
            Reason reason,
            String reasonLabel,
            String detail) {}

    /**
     * @param reporting   fix in the last 2 minutes ({@link BusLiveStatus#isReporting})
     * @param lagging     last fix 2-5 minutes old: probably between updates, so not diagnosed
     */
    public record Counts(int assigned, int reporting, int lagging, int silentToday, int notSeenToday) {}

    public record ReasonCount(Reason reason, String label, int count) {}

    /**
     * @param primaryReason the commonest reason among buses silent today, or null if none are
     * @param notChecked    checks that could not run for lack of data, as sentences for the admin
     */
    public record RouteReport(
            String routeCode,
            String routeName,
            long generatedAt,
            long serviceDayStart,
            Counts counts,
            Reason primaryReason,
            String summary,
            List<ReasonCount> reasons,
            List<BusDiagnosis> buses,
            List<String> notChecked) {}

    public record RouteSummary(String routeCode, String routeName, Counts counts) {}

    /** Routes with buses silent today, most silent first; {@code totalRoutes} counts all of them. */
    public record NotRespondingRoutes(List<RouteSummary> routes, int totalRoutes, long generatedAt, long serviceDayStart) {}

    /** A bus on the route: its registry row (null if only seen in the feed) and latest fix (null if never seen). */
    record Member(Bus bus, BusLocationEvent fix) {}

    record Terminal(String name, double latitude, double longitude) {}

    /** Everything the rules read besides the buses themselves. */
    record Context(
            long now,
            long serviceDayStart,
            boolean feedHealthy,
            Long feedLastFetchAt,
            Set<String> cancelledTrips,
            boolean tripUpdatesConfigured,
            List<Terminal> terminals) {}

    private final BusRepository busRepository;
    /** Registry from memory (shared with the fleet list); the scan runs every 30 s per open console. */
    private final BusRegistryCache registry;
    private final BusLocationStore busLocationStore;
    private final RouteRepository routeRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;
    private final FeedHealthService feedHealthService;
    private final RealtimeSnapshot snapshot;
    private final boolean tripUpdatesConfigured;

    public RouteDiagnosisService(BusRepository busRepository, BusRegistryCache registry, BusLocationStore busLocationStore,
                                 RouteRepository routeRepository, TripRepository tripRepository,
                                 StopTimeRepository stopTimeRepository, FeedHealthService feedHealthService,
                                 RealtimeSnapshot snapshot,
                                 @Value("${dtc.trip-updates.url:}") String tripUpdatesUrl) {
        this.busRepository = busRepository;
        this.registry = registry;
        this.busLocationStore = busLocationStore;
        this.routeRepository = routeRepository;
        this.tripRepository = tripRepository;
        this.stopTimeRepository = stopTimeRepository;
        this.feedHealthService = feedHealthService;
        this.snapshot = snapshot;
        this.tripUpdatesConfigured = !tripUpdatesUrl.isBlank();
    }

    /** Per-route counts for the routes with at least one bus silent today. */
    public NotRespondingRoutes notResponding(int limit) {
        if (limit < 1 || limit > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 500.");
        long now = Instant.now().getEpochSecond();
        long dayStart = serviceDayStart(now);
        Map<String, List<Member>> byRoute = membersByRoute();
        Map<String, String> names = routeNames(byRoute);

        List<RouteSummary> routes = new ArrayList<>();
        byRoute.forEach((code, members) -> {
            Counts counts = count(members, now, dayStart);
            if (counts.silentToday() > 0) routes.add(new RouteSummary(code, names.get(code), counts));
        });
        routes.sort(Comparator.comparingInt((RouteSummary r) -> r.counts().silentToday()).reversed()
                .thenComparingInt(r -> r.counts().reporting())
                .thenComparing(RouteSummary::routeCode));
        return new NotRespondingRoutes(List.copyOf(routes.subList(0, Math.min(limit, routes.size()))),
                routes.size(), now, dayStart);
    }

    /** The diagnosis for one route, by GTFS route_id. 404 if no bus has ever been on it. */
    @Transactional(readOnly = true)
    public RouteReport diagnose(String routeCode) {
        List<Member> members = membersByRoute().get(routeCode);
        if (members == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No buses are known on route " + routeCode + ".");
        }
        Route route = routeRepository.findByRouteCode(routeCode).orElse(null);
        long now = Instant.now().getEpochSecond();
        var feed = feedHealthService.health();
        Context ctx = new Context(now, serviceDayStart(now), feed.healthy(), feed.lastFetchAt(),
                snapshot.cancelledTrips(), tripUpdatesConfigured, terminals(route));
        return diagnose(routeCode, route != null ? route.getName() : null, members, ctx);
    }

    // ── Rules (pure, so they can be tested without Spring) ───────────────────

    static RouteReport diagnose(String routeCode, String routeName, List<Member> members, Context ctx) {
        List<BusDiagnosis> buses = new ArrayList<>();
        for (Member m : members) {
            if (m.fix() != null && heardWithin(m.fix(), ctx.now(), SILENT_AFTER_SECONDS)) continue;
            buses.add(classify(m, ctx));
        }
        // Silent buses first, most recently heard first; buses with no fix at all last.
        buses.sort(Comparator.comparing(BusDiagnosis::category)
                .thenComparing(BusDiagnosis::lastSeen, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(BusDiagnosis::vehicleId, Comparator.nullsLast(Comparator.naturalOrder())));

        Counts counts = count(members, ctx.now(), ctx.serviceDayStart());
        Map<Reason, Integer> tally = new EnumMap<>(Reason.class);
        for (BusDiagnosis b : buses) tally.merge(b.reason(), 1, Integer::sum);
        List<ReasonCount> reasons = tally.entrySet().stream()
                .map(e -> new ReasonCount(e.getKey(), e.getKey().label, e.getValue()))
                .sorted(Comparator.comparingInt(ReasonCount::count).reversed())
                .toList();
        Reason primary = primaryReason(buses);
        int flaggedNotSeen = (int) buses.stream()
                .filter(b -> b.category() == Category.NOT_SEEN_TODAY && b.reason() != Reason.NOT_OUT_TODAY)
                .count();
        String label = routeName != null && !routeName.isBlank() ? routeName : routeCode;
        return new RouteReport(routeCode, routeName, ctx.now(), ctx.serviceDayStart(), counts, primary,
                summary(label, counts, primary, primary != null ? tally.get(primary) : 0, flaggedNotSeen),
                reasons, List.copyOf(buses), notChecked(ctx));
    }

    static BusDiagnosis classify(Member m, Context ctx) {
        Bus bus = m.bus();
        BusLocationEvent fix = m.fix();
        BusStatus status = bus != null ? bus.getStatus() : null;
        boolean seenToday = fix != null && fix.getTimestamp() != null && fix.getTimestamp() >= ctx.serviceDayStart();
        Category category = seenToday ? Category.SILENT_TODAY : Category.NOT_SEEN_TODAY;

        Reason reason;
        String detail;
        if (status == BusStatus.MAINTENANCE) {
            reason = Reason.IN_MAINTENANCE;
            detail = "Marked as in maintenance in the bus registry.";
        } else if (status == BusStatus.INACTIVE) {
            reason = Reason.WITHDRAWN;
            detail = "Marked as inactive in the bus registry.";
        } else if (!seenToday) {
            reason = Reason.NOT_OUT_TODAY;
            detail = "No position since midnight: off duty or not scheduled today (there is no roster to confirm).";
        } else if (lostWithFeed(fix, ctx)) {
            reason = Reason.FEED_OUTAGE;
            detail = "Was reporting until the live feed stopped updating; every bus is affected.";
        } else if (fix.getTripId() != null && ctx.cancelledTrips().contains(fix.getTripId())) {
            reason = Reason.TRIP_CANCELLED;
            detail = "Its trip " + fix.getTripId() + " is cancelled in the trip updates feed.";
        } else if (fix.getSpeedKmh() == null) {
            reason = Reason.SIGNAL_LOSS;
            detail = "Last fix had no speed, so it is unknown whether the bus was moving.";
        } else if (fix.getSpeedKmh() >= BusLiveStatus.SLOW_SPEED_KMH) {
            reason = Reason.GPS_LOST_WHILE_MOVING;
            detail = "Moving at " + Math.round(fix.getSpeedKmh()) + " km/h when it stopped reporting: "
                    + "likely a GPS unit or mobile data fault mid-trip.";
        } else {
            Nearest near = nearestTerminal(fix, ctx.terminals());
            if (near != null && near.metres() <= TERMINAL_RADIUS_M) {
                reason = Reason.STOPPED_AT_TERMINAL;
                detail = "Stopped " + Math.round(near.metres()) + " m from " + near.terminal().name()
                        + ": likely ended its trip or is held at the terminal with the unit off.";
            } else if (near != null) {
                reason = Reason.STOPPED_ON_ROUTE;
                detail = "Stopped away from the route's terminals when it went silent: possible breakdown "
                        + "or engine off. Check with the depot.";
            } else {
                reason = Reason.SIGNAL_LOSS;
                detail = "Stopped when it went silent; this route has no stop sequence to tell a terminal "
                        + "from the open road.";
            }
        }

        return new BusDiagnosis(
                bus != null ? bus.getVehicleId() : fix.getVehicleId(),
                bus != null ? bus.getRegistrationNumber() : null,
                status, category,
                fix != null ? fix.getTimestamp() : null,
                fix != null ? fix.getLatitude() : null,
                fix != null ? fix.getLongitude() : null,
                fix != null ? fix.getSpeedKmh() : null,
                fix != null ? fix.getTripId() : null,
                reason, reason.label, detail);
    }

    /** The feed is down, and this bus was still fresh at the last good fetch (so the feed is why it went quiet). */
    private static boolean lostWithFeed(BusLocationEvent fix, Context ctx) {
        if (ctx.feedHealthy()) return false;
        if (ctx.feedLastFetchAt() == null) return true; // nothing fetched since start: no bus can report
        return fix.getTimestamp() >= ctx.feedLastFetchAt() - BusLiveStatus.STALE_AFTER_SECONDS;
    }

    /** Commonest reason among buses silent today (ties go to the earlier, more specific reason). */
    static Reason primaryReason(List<BusDiagnosis> buses) {
        Map<Reason, Integer> silent = new EnumMap<>(Reason.class);
        for (BusDiagnosis b : buses) {
            if (b.category() == Category.SILENT_TODAY) silent.merge(b.reason(), 1, Integer::sum);
        }
        Reason best = null;
        for (var e : silent.entrySet()) {
            if (best == null || e.getValue() > silent.get(best)) best = e.getKey();
        }
        return best;
    }

    static String summary(String route, Counts c, Reason primary, int primaryCount, int flaggedNotSeen) {
        StringBuilder s = new StringBuilder("Route ").append(route).append(": ")
                .append(c.reporting()).append(" of ").append(c.assigned()).append(" known buses reporting now");
        if (c.lagging() > 0) s.append(", ").append(c.lagging()).append(" more between updates");
        s.append('.');
        if (c.silentToday() == 0) {
            s.append(" No bus has gone silent today.");
        } else {
            s.append(' ').append(buses(c.silentToday())).append(" went silent today");
            if (primary != null) {
                s.append("; main reason: ").append(primary.label.toLowerCase())
                        .append(" (").append(primaryCount).append(')');
            }
            s.append('.');
        }
        if (c.notSeenToday() > 0) {
            s.append(' ').append(buses(c.notSeenToday())).append(" not seen today");
            if (flaggedNotSeen > 0) s.append(", ").append(flaggedNotSeen).append(" of them in maintenance or withdrawn");
            s.append('.');
        }
        return s.toString();
    }

    private static String buses(int n) {
        return n + (n == 1 ? " bus" : " buses");
    }

    static List<String> notChecked(Context ctx) {
        List<String> out = new ArrayList<>();
        out.add("Driver and crew: there is no roster or shift data, so unfilled duties cannot be detected.");
        out.add("Maintenance: only the bus registry's status flag is read; there is no maintenance or defect log.");
        out.add("Depot hold-ups: there are no depot locations or per-bus departure times; stopped buses are "
                + "compared with the route's end stops instead.");
        if (!ctx.tripUpdatesConfigured()) {
            out.add("Cancellations: no trip updates feed is configured (dtc.trip-updates.url), so cancelled trips are unknown.");
        }
        if (ctx.terminals().isEmpty()) {
            out.add("Terminals: this route has no stop sequence, so a stopped bus cannot be placed at a terminal.");
        }
        return out;
    }

    static Counts count(List<Member> members, long now, long dayStart) {
        int reporting = 0, lagging = 0, silent = 0, notSeen = 0;
        for (Member m : members) {
            BusLocationEvent fix = m.fix();
            if (fix != null && BusLiveStatus.isReporting(fix, now)) reporting++;
            else if (fix != null && heardWithin(fix, now, SILENT_AFTER_SECONDS)) lagging++;
            else if (fix != null && fix.getTimestamp() != null && fix.getTimestamp() >= dayStart) silent++;
            else notSeen++;
        }
        return new Counts(members.size(), reporting, lagging, silent, notSeen);
    }

    private static boolean heardWithin(BusLocationEvent fix, long now, long seconds) {
        return fix.getTimestamp() != null && now - fix.getTimestamp() <= seconds;
    }

    record Nearest(Terminal terminal, double metres) {}

    private static Nearest nearestTerminal(BusLocationEvent fix, List<Terminal> terminals) {
        if (!hasPosition(fix)) return null;
        Nearest best = null;
        for (Terminal t : terminals) {
            double d = Geometry.distance(fix.getLatitude(), fix.getLongitude(), t.latitude(), t.longitude());
            if (best == null || d < best.metres()) best = new Nearest(t, d);
        }
        return best;
    }

    private static boolean hasPosition(BusLocationEvent fix) {
        return fix.getLatitude() != null && fix.getLongitude() != null;
    }

    static long serviceDayStart(long nowEpochSeconds) {
        return LocalDate.ofInstant(Instant.ofEpochSecond(nowEpochSeconds), SERVICE_ZONE)
                .atStartOfDay(SERVICE_ZONE).toEpochSecond();
    }

    // ── Data gathering ───────────────────────────────────────────────────────

    /** Every known bus grouped by route code; buses with no route at all are left out. */
    private Map<String, List<Member>> membersByRoute() {
        Map<String, BusLocationEvent> latest = new HashMap<>();
        for (BusLocationEvent e : busLocationStore.findAll()) {
            if (e.getVehicleId() != null) latest.put(e.getVehicleId(), e);
        }
        Map<String, List<Member>> byRoute = new LinkedHashMap<>();
        for (Bus bus : registry.all()) {
            add(byRoute, new Member(bus, latest.remove(bus.getVehicleId())));
        }
        for (BusLocationEvent orphan : latest.values()) add(byRoute, new Member(null, orphan));
        return byRoute;
    }

    private static void add(Map<String, List<Member>> byRoute, Member m) {
        String code = m.fix() != null && m.fix().getRouteId() != null ? m.fix().getRouteId()
                : m.bus() != null && m.bus().getRoute() != null ? m.bus().getRoute().getRouteCode() : null;
        if (code != null) byRoute.computeIfAbsent(code, k -> new ArrayList<>()).add(m);
    }

    private Map<String, String> routeNames(Map<String, List<Member>> byRoute) {
        Map<String, String> names = new HashMap<>();
        Set<String> missing = new HashSet<>(byRoute.keySet());
        for (List<Member> members : byRoute.values()) {
            for (Member m : members) {
                Route r = m.bus() != null ? m.bus().getRoute() : null;
                if (r != null && r.getName() != null && byRoute.containsKey(r.getRouteCode())) {
                    names.putIfAbsent(r.getRouteCode(), r.getName());
                    missing.remove(r.getRouteCode());
                }
            }
        }
        if (!missing.isEmpty()) {
            for (Route r : routeRepository.findByRouteCodeIn(missing)) {
                if (r.getName() != null) names.putIfAbsent(r.getRouteCode(), r.getName());
            }
        }
        return names;
    }

    /** First and last stop of a representative trip; empty when the route has no stop_times. */
    private List<Terminal> terminals(Route route) {
        if (route == null) return List.of();
        return tripRepository.findFirstByRoute_Id(route.getId())
                .map(trip -> stopTimeRepository.findByTrip_IdOrderByStopSequenceAsc(trip.getId()))
                .filter(times -> !times.isEmpty())
                .map(times -> {
                    List<Terminal> ends = new ArrayList<>(2);
                    addTerminal(ends, times.get(0).getStop());
                    if (times.size() > 1) addTerminal(ends, times.get(times.size() - 1).getStop());
                    return List.copyOf(ends);
                })
                .orElse(List.of());
    }

    private static void addTerminal(List<Terminal> ends, Stop stop) {
        if (stop == null || stop.getLatitude() == null || stop.getLongitude() == null) return;
        ends.add(new Terminal(Objects.requireNonNullElse(stop.getName(), "the terminal"),
                stop.getLatitude(), stop.getLongitude()));
    }
}
