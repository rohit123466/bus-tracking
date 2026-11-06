package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.BusStatus;
import com.dtc.bus_tracker.service.RouteDiagnosisService.Category;
import com.dtc.bus_tracker.service.RouteDiagnosisService.Context;
import com.dtc.bus_tracker.service.RouteDiagnosisService.Member;
import com.dtc.bus_tracker.service.RouteDiagnosisService.Reason;
import com.dtc.bus_tracker.service.RouteDiagnosisService.Terminal;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteDiagnosisServiceTest {

    // 15:00 IST, so the service day started 15 hours earlier.
    private static final long NOW = ZonedDateTime.of(2026, 11, 5, 15, 0, 0, 0, RouteDiagnosisService.SERVICE_ZONE).toEpochSecond();
    private static final long DAY_START = NOW - 15 * 3600;

    private static final Terminal MORI_GATE = new Terminal("Mori Gate Terminal", 28.6670, 77.2280);
    private static final Terminal NEHRU_PLACE = new Terminal("Nehru Place Terminal", 28.5490, 77.2510);

    private static Context ctx(boolean feedHealthy, Long lastFetchAt, Set<String> cancelled, List<Terminal> terminals) {
        return new Context(NOW, DAY_START, feedHealthy, lastFetchAt, cancelled, true, terminals);
    }

    private static final Context OK = ctx(true, NOW - 4, Set.of(), List.of(MORI_GATE, NEHRU_PLACE));

    private static Bus bus(String id, BusStatus status) {
        return Bus.builder().vehicleId(id).registrationNumber("REG-" + id).status(status).build();
    }

    private static BusLocationEvent fix(String id, long ageSeconds, Double speed, double lat, double lng) {
        return BusLocationEvent.builder().vehicleId(id).routeId("1052").tripId("T-" + id)
                .latitude(lat).longitude(lng).timestamp(NOW - ageSeconds).speedKmh(speed).build();
    }

    private static Reason reasonOf(Member m, Context c) {
        return RouteDiagnosisService.classify(m, c).reason();
    }

    @Test void serviceDayStartsAtMidnightInDelhi() {
        assertEquals(DAY_START, RouteDiagnosisService.serviceDayStart(NOW));
    }

    @Test void registryFlagsWinOverEverythingElse() {
        var moving = fix("A", 600, 30.0, 28.60, 77.20);
        assertEquals(Reason.IN_MAINTENANCE, reasonOf(new Member(bus("A", BusStatus.MAINTENANCE), moving), OK));
        assertEquals(Reason.WITHDRAWN, reasonOf(new Member(bus("A", BusStatus.INACTIVE), null), OK));
    }

    @Test void busWithNoFixTodayIsNotOutRatherThanDiagnosed() {
        var yesterday = fix("A", 20 * 3600, 30.0, 28.60, 77.20);
        var d = RouteDiagnosisService.classify(new Member(bus("A", BusStatus.ACTIVE), yesterday), OK);
        assertEquals(Category.NOT_SEEN_TODAY, d.category());
        assertEquals(Reason.NOT_OUT_TODAY, d.reason());
        assertEquals(Reason.NOT_OUT_TODAY, reasonOf(new Member(bus("B", BusStatus.ACTIVE), null), OK));
    }

    @Test void feedOutageOnlyBlamedForBusesThatWereFreshWhenTheFeedStopped() {
        long lastFetch = NOW - 900;
        var down = ctx(false, lastFetch, Set.of(), List.of(MORI_GATE));
        // Last fix 950 s ago: within 120 s of the last good fetch.
        assertEquals(Reason.FEED_OUTAGE, reasonOf(new Member(null, fix("A", 950, 30.0, 28.60, 77.20)), down));
        // Already silent an hour before the feed went down: the feed is not why.
        assertEquals(Reason.GPS_LOST_WHILE_MOVING, reasonOf(new Member(null, fix("B", 4500, 30.0, 28.60, 77.20)), down));
        // Nothing fetched since start-up.
        assertEquals(Reason.FEED_OUTAGE, reasonOf(new Member(null, fix("C", 4500, 30.0, 28.60, 77.20)),
                ctx(false, null, Set.of(), List.of())));
    }

    @Test void cancelledTripFromTheTripUpdatesFeed() {
        var c = ctx(true, NOW - 4, Set.of("T-A"), List.of(MORI_GATE));
        assertEquals(Reason.TRIP_CANCELLED, reasonOf(new Member(null, fix("A", 600, 30.0, 28.60, 77.20)), c));
    }

    @Test void lastMotionSeparatesGpsLossTerminalAndRoadStops() {
        assertEquals(Reason.GPS_LOST_WHILE_MOVING, reasonOf(new Member(null, fix("A", 600, 32.0, 28.60, 77.20)), OK));
        // ~110 m from Mori Gate.
        var atTerminal = RouteDiagnosisService.classify(new Member(null, fix("B", 600, 0.0, 28.6680, 77.2280)), OK);
        assertEquals(Reason.STOPPED_AT_TERMINAL, atTerminal.reason());
        assertTrue(atTerminal.detail().contains("Mori Gate Terminal"), atTerminal.detail());
        assertEquals(Reason.STOPPED_ON_ROUTE, reasonOf(new Member(null, fix("C", 600, 1.0, 28.60, 77.20)), OK));
        // No stop sequence for the route: can't tell a terminal from the road.
        assertEquals(Reason.SIGNAL_LOSS, reasonOf(new Member(null, fix("D", 600, 0.0, 28.60, 77.20)),
                ctx(true, NOW - 4, Set.of(), List.of())));
        assertEquals(Reason.SIGNAL_LOSS, reasonOf(new Member(null, fix("E", 600, null, 28.60, 77.20)), OK));
    }

    @Test void reportCountsListsOnlySilentOrAbsentBusesAndPicksTheMainReason() {
        List<Member> members = List.of(
                new Member(bus("A", BusStatus.ACTIVE), fix("A", 10, 30.0, 28.60, 77.20)),     // reporting
                new Member(bus("G", BusStatus.ACTIVE), fix("G", 200, 30.0, 28.60, 77.20)),    // between updates
                new Member(bus("B", BusStatus.ACTIVE), fix("B", 400, 30.0, 28.60, 77.20)),    // GPS lost
                new Member(bus("C", BusStatus.ACTIVE), fix("C", 1200, 25.0, 28.60, 77.20)),   // GPS lost
                new Member(bus("D", BusStatus.ACTIVE), fix("D", 900, 0.0, 28.6680, 77.2280)), // at terminal
                new Member(bus("E", BusStatus.MAINTENANCE), null),                            // not out, flagged
                new Member(bus("F", BusStatus.ACTIVE), null));                                // not out

        var report = RouteDiagnosisService.diagnose("1052", "534", members, OK);

        assertEquals(new RouteDiagnosisService.Counts(7, 1, 1, 3, 2), report.counts());
        assertEquals(List.of("B", "D", "C", "E", "F"),
                report.buses().stream().map(RouteDiagnosisService.BusDiagnosis::vehicleId).toList());
        assertEquals(Reason.GPS_LOST_WHILE_MOVING, report.primaryReason());
        assertEquals(Reason.GPS_LOST_WHILE_MOVING, report.reasons().get(0).reason());
        assertEquals(2, report.reasons().get(0).count());
        assertEquals("Route 534: 1 of 7 known buses reporting now, 1 more between updates. 3 buses went silent today; main reason: "
                + "gps lost while moving (2). 2 buses not seen today, 1 of them in maintenance or withdrawn.",
                report.summary());
        assertTrue(report.notChecked().stream().anyMatch(s -> s.startsWith("Driver and crew")));
    }

    @Test void quietRouteHasNoPrimaryReasonAndSaysSo() {
        var report = RouteDiagnosisService.diagnose("1052", null,
                List.of(new Member(null, fix("A", 10, 30.0, 28.60, 77.20))), OK);
        assertNull(report.primaryReason());
        assertTrue(report.buses().isEmpty());
        assertEquals("Route 1052: 1 of 1 known buses reporting now. No bus has gone silent today.", report.summary());
    }

    @Test void missingDataIsListedAsNotChecked() {
        var bare = new Context(NOW, DAY_START, true, NOW - 4, Set.of(), false, List.of());
        var notChecked = RouteDiagnosisService.notChecked(bare);
        assertTrue(notChecked.stream().anyMatch(s -> s.startsWith("Cancellations")));
        assertTrue(notChecked.stream().anyMatch(s -> s.startsWith("Terminals")));
        assertFalse(RouteDiagnosisService.notChecked(OK).stream().anyMatch(s -> s.startsWith("Cancellations")));
    }
}
