package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface StopRepository extends JpaRepository<Stop, Long> {

    Optional<Stop> findByStopId(String stopId);

    /** id, name, latitude, longitude of every stop in id order, without loading entities. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT s.id, s.name, s.latitude, s.longitude FROM Stop s ORDER BY s.id")
    java.util.List<Object[]> findAllPoints();

    /** The given stops with their routes, in one query instead of one per stop. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT s FROM Stop s LEFT JOIN FETCH s.routes WHERE s.id IN :ids ORDER BY s.id")
    java.util.List<Stop> findWithRoutesByIdIn(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}