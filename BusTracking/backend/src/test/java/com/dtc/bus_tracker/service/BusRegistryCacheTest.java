package com.dtc.bus_tracker.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.repository.BusRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The admin registry is read from the database once, until a bus row changes. */
class BusRegistryCacheTest {

    @Test
    void registryIsReadOnceUntilABusRowChanges() {
        var repo = mock(BusRepository.class);
        var bus = Bus.builder().vehicleId("DL1").build();
        when(repo.findAllWithRoute()).thenReturn(List.of(bus));
        var registry = new BusRegistryCache(repo);

        assertEquals(List.of(bus), registry.all());
        registry.all();
        assertEquals(1, registry.count());
        verify(repo, times(1)).findAllWithRoute();

        registry.invalidate();
        registry.all();
        verify(repo, times(2)).findAllWithRoute();
    }
}
