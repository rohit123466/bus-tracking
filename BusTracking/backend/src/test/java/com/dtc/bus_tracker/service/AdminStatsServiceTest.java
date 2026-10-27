package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.RouteStat;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.repository.RouteRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminStatsServiceTest {

    private final BusRepository buses = mock(BusRepository.class);
    private final BusLocationStore store = mock(BusLocationStore.class);
    private final RouteRepository routes = mock(RouteRepository.class);
    private final AdminStatsService service = new AdminStatsService(buses, store, routes);

    static BusLocationEvent bus(String id, String route, long ageSeconds, Double speed) {
        return BusLocationEvent.builder().vehicleId(id).routeId(route)
                .timestamp(System.currentTimeMillis() / 1000 - ageSeconds).speedKmh(speed).build();
    }

    @Test void countsFollowTheSharedLiveStatusRules() {
        when(buses.count()).thenReturn(10L);
        when(store.findAll()).thenReturn(List.of(
                bus("A", "r1", 5, 30.0),
                bus("B", "r1", 5, 2.0),     // slow
                bus("C", "r2", 5, null),    // reporting, speed unknown
                bus("D", "r2", 600, 1.0))); // stale: neither live nor slow
        when(routes.findByRouteCodeIn(any())).thenReturn(List.of(Route.builder().routeCode("r1").name("990A").build()));

        var stats = service.getStats();

        assertEquals(3, stats.getLiveBusCount());
        assertEquals(7, stats.getOfflineBusCount());
        assertEquals(1, stats.getDelayedBusCount());
        assertEquals(16.0, stats.getAverageSpeedKmh());
        assertEquals(List.of("r1", "r2"), stats.getRouteStats().stream().map(RouteStat::getRouteCode).sorted().toList());
        RouteStat r1 = stats.getRouteStats().stream().filter(r -> r.getRouteCode().equals("r1")).findFirst().orElseThrow();
        assertEquals("990A", r1.getRouteName());
        assertEquals(2, r1.getActiveBusCount());
    }

    @Test void noLiveBusesSkipsTheRouteLookup() {
        when(store.findAll()).thenReturn(List.of());
        var stats = service.getStats();
        assertEquals(0, stats.getLiveBusCount());
        assertTrue(stats.getRouteStats().isEmpty());
        verifyNoInteractions(routes);
    }
}
