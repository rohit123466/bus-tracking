package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Trip;
import com.dtc.bus_tracker.entity.TripStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {
    Optional<Trip> findByTripId(String tripId);
    Optional<Trip> findFirstByRoute_Id(Long routeId);

    /** The journey a bus is currently running, i.e. the one tickets are sold against. */
    Optional<Trip> findFirstByBus_IdAndStatusOrderByIdDesc(Long busId, TripStatus status);

    /**
     * Row-locks the trip so concurrent ticket sales/stop advances on the same
     * journey are serialized - otherwise two conductors' requests could both
     * see a seat as free and both sell it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    Optional<Trip> lockById(@Param("id") Long id);

    /** Whether any trip on this route is marked wheelchair-accessible in the GTFS feed. */
    boolean existsByRoute_IdAndWheelchairAccessibleTrue(Long routeId);
}