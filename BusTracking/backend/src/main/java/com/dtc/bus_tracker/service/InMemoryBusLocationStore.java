package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for Redis when running the "demo" profile on a machine without
 * Redis available. Same contract as {@link RedisBusLocationStore}.
 */
@Service
@Profile("demo")
public class InMemoryBusLocationStore implements BusLocationStore {

    private final ConcurrentHashMap<String, BusLocationEvent> buses = new ConcurrentHashMap<>();

    @Override
    public void save(BusLocationEvent event) {
        buses.put(event.getVehicleId(), event);
    }

    @Override
    public Collection<BusLocationEvent> findAll() {
        buses.values().removeIf(e -> e.getTimestamp() == null || !com.dtc.bus_tracker.live.RealtimeSnapshot.fresh(e.getTimestamp()));
        return java.util.List.copyOf(buses.values());
    }

    @Override
    public Optional<BusLocationEvent> findByVehicleId(String vehicleId) {
        return Optional.ofNullable(buses.get(vehicleId))
            .filter(e -> e.getTimestamp() != null && com.dtc.bus_tracker.live.RealtimeSnapshot.fresh(e.getTimestamp()));
    }
}
