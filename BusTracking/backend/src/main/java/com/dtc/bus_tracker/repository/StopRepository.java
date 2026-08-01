package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Stop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface StopRepository extends JpaRepository<Stop, Long> {

    Optional<Stop> findByStopId(String stopId);
}