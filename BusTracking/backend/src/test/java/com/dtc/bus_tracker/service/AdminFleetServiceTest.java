package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.dto.FleetBusResponse;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.BusStatus;
import com.dtc.bus_tracker.entity.BusType;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.repository.BusRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminFleetServiceTest {

    private final BusRepository buses = mock(BusRepository.class);
    private final BusLocationStore store = mock(BusLocationStore.class);
    private final AdminFleetService service = new AdminFleetService(buses, store);

    private final Route r534 = Route.builder().routeCode("1052").name("534").build();
    private final Route r990 = Route.builder().routeCode("2001").name("990A").build();

    AdminFleetServiceTest() {
        when(buses.findAllWithRoute()).thenReturn(List.of(
                Bus.builder().vehicleId("DL1PC0001").registrationNumber("DL 1PC 0001").route(r534)
                        .status(BusStatus.ACTIVE).busType(BusType.LOW_FLOOR).cameraInstalled(true).build(),
                Bus.builder().vehicleId("DL1PC0002").route(r534).status(BusStatus.ACTIVE).build(),
                Bus.builder().vehicleId("DL1PC0003").route(r990).status(BusStatus.ACTIVE).build(),
                Bus.builder().vehicleId("DL1PC0004").route(r990).status(BusStatus.INACTIVE).build()));
        when(store.findAll()).thenReturn(List.of(
                fix("DL1PC0001", "1052", 5, 32.0),
                fix("DL1PC0002", "1052", 8, 1.5),
                fix("DL1PC0003", "2001", 900, 20.0),
                // In the live store but missing from the registry.
                fix("DL1PC0099", "2001", 3, 18.0)));
    }

    static BusLocationEvent fix(String id, String route, long ageSeconds, double speed) {
        return BusLocationEvent.builder().vehicleId(id).routeId(route).latitude(28.7).longitude(77.1)
                .timestamp(System.currentTimeMillis() / 1000 - ageSeconds).speedKmh(speed).build();
    }

    private static List<String> ids(List<FleetBusResponse> rows) {
        return rows.stream().map(FleetBusResponse::getVehicleId).toList();
    }

    @Test void joinsRegistryWithLatestPositionAndDerivesLiveStatus() {
        var page = service.list(null, null, null, "vehicleId", 0, 50);
        assertEquals(5, page.getTotalElements());
        var first = page.getContent().get(0);
        assertEquals("DL1PC0001", first.getVehicleId());
        assertEquals("DL 1PC 0001", first.getRegistrationNumber());
        assertEquals("534", first.getRouteName());
        assertEquals(BusType.LOW_FLOOR, first.getBusType());
        assertEquals(BusLiveStatus.MOVING, first.getLiveStatus());
        assertEquals(32.0, first.getSpeedKmh());
        assertNotNull(first.getLastSeen());

        assertEquals(BusLiveStatus.SLOW, page.getContent().get(1).getLiveStatus());
        assertEquals(BusLiveStatus.STALE, page.getContent().get(2).getLiveStatus());
        var noData = page.getContent().get(3);
        assertEquals(BusLiveStatus.NO_DATA, noData.getLiveStatus());
        assertNull(noData.getLastSeen());
        var orphan = page.getContent().get(4);
        assertEquals("DL1PC0099", orphan.getVehicleId());
        assertEquals("2001", orphan.getRouteCode());
    }

    @Test void filtersByQueryRouteAndStatus() {
        assertEquals(List.of("DL1PC0001"), ids(service.list("1pc 0001", null, null, "vehicleId", 0, 50).getContent()));
        assertEquals(List.of("DL1PC0001", "DL1PC0002"), ids(service.list(null, null, "534", "vehicleId", 0, 50).getContent()));
        assertEquals(List.of("DL1PC0001", "DL1PC0002"), ids(service.list(null, null, "1052", "vehicleId", 0, 50).getContent()));
        assertEquals(List.of("DL1PC0001", "DL1PC0002", "DL1PC0099"),
                ids(service.list(null, "moving, SLOW", null, "vehicleId", 0, 50).getContent()));
    }

    @Test void statusCountsIgnoreTheStatusFilterButNotTheOthers() {
        var page = service.list(null, "SLOW", "534", null, 0, 50);
        assertEquals(1, page.getTotalElements());
        assertEquals(1L, page.getStatusCounts().get(BusLiveStatus.MOVING));
        assertEquals(1L, page.getStatusCounts().get(BusLiveStatus.SLOW));
        assertEquals(0L, page.getStatusCounts().get(BusLiveStatus.STALE));
    }

    @Test void sortsWithMissingValuesLastInBothDirections() {
        assertEquals("DL1PC0004", ids(service.list(null, null, null, "lastSeen,desc", 0, 50).getContent()).get(4));
        assertEquals("DL1PC0004", ids(service.list(null, null, null, "lastSeen,asc", 0, 50).getContent()).get(4));
        assertEquals(List.of("DL1PC0001", "DL1PC0003", "DL1PC0099", "DL1PC0002", "DL1PC0004"),
                ids(service.list(null, null, null, "speed,desc", 0, 50).getContent()));
    }

    @Test void paginates() {
        var page = service.list(null, null, null, "vehicleId", 1, 2);
        assertEquals(List.of("DL1PC0003", "DL1PC0004"), ids(page.getContent()));
        assertEquals(3, page.getTotalPages());
        assertTrue(service.list(null, null, null, "vehicleId", 9, 2).getContent().isEmpty());
    }

    @Test void rejectsUnknownParametersWithBadRequest() {
        for (Runnable call : List.<Runnable>of(
                () -> service.list(null, "PARKED", null, null, 0, 50),
                () -> service.list(null, null, null, "colour", 0, 50),
                () -> service.list(null, null, null, "speed,sideways", 0, 50),
                () -> service.list(null, null, null, null, -1, 50),
                () -> service.list(null, null, null, null, 0, AdminFleetService.MAX_SIZE + 1))) {
            var ex = assertThrows(ResponseStatusException.class, call::run);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
    }
}
