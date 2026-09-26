package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusRepository extends JpaRepository<Bus, Long> {
    Optional<Bus> findByVehicleId(String vehicleId);
    List<Bus> findByRouteId(Long routeId);

    /** Buses with a seat layout, i.e. the ones taking part in seat-level ticketing. */
    List<Bus> findByBusTypeIsNotNullOrderByVehicleIdAsc();
}