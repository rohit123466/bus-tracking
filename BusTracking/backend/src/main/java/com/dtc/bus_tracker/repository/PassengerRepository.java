package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PassengerRepository extends JpaRepository<Passenger, Long> {
    List<Passenger> findAllByOrderByNameAsc();
}
