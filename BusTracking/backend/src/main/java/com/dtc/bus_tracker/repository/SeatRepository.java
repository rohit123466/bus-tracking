package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByBus_IdOrderByIdAsc(Long busId);
    boolean existsByBus_Id(Long busId);
}
