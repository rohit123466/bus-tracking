package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Bus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusRepository extends JpaRepository<Bus, Long> {
    Optional<Bus> findByVehicleId(String vehicleId);
    List<Bus> findByRouteId(Long routeId);

    /** Buses with a seat layout, i.e. the ones taking part in seat-level ticketing. */
    List<Bus> findByBusTypeIsNotNullOrderByVehicleIdAsc();

    /** Every bus with its route, in one query (the admin fleet list reads all of them). */
    @Query("SELECT b FROM Bus b LEFT JOIN FETCH b.route")
    List<Bus> findAllWithRoute();
}