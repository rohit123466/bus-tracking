package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);
    Optional<Trip> findFirstByRoute_Id(Long routeId);

    /** Whether any trip on this route is marked wheelchair-accessible in the GTFS feed. */
    boolean existsByRoute_IdAndWheelchairAccessibleTrue(Long routeId);
}