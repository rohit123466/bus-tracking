package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.repository.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyFreshnessTest {
  @Test void cacheRemovesStalePositionsAndNearbyDoesNotInventEta() {
    var store = new InMemoryBusLocationStore();
    long now = Instant.now().getEpochSecond();
    store.save(BusLocationEvent.builder().vehicleId("old").timestamp(now - 121)
        .latitude(28.6).longitude(77.2).build());
    assertTrue(store.findByVehicleId("old").isEmpty());
    assertTrue(store.findAll().isEmpty());
    store.save(BusLocationEvent.builder().vehicleId("fresh").timestamp(now)
        .latitude(28.6).longitude(77.2).build());
    var stops = mock(StopRepository.class);
    when(stops.findAll()).thenReturn(List.of());
    var nearby = new NearbyBusService(stops, store, mock(RouteRepository.class), mock(TripRepository.class));
    var buses = nearby.findNearbyBuses(28.601, 77.2, 1000, 10);
    assertEquals(1, buses.size());
    assertNull(buses.getFirst().getEtaMinutes());
  }
}
