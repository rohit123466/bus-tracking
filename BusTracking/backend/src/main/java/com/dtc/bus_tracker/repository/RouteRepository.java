package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {
    Optional<Route> findByRouteCode(String routeCode);

    // Fetches every route's stops in one query instead of one query per route.
    @Query("SELECT DISTINCT r FROM Route r LEFT JOIN FETCH r.stops")
    List<Route> findAllWithStops();
}