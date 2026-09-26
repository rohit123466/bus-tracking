package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.CameraOccupancy;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CameraOccupancyRepository extends JpaRepository<CameraOccupancy, Long> {

    @Query("SELECT COALESCE(MAX(c.scanNumber), 0) FROM CameraOccupancy c WHERE c.bus.id = :busId")
    int findLatestScanNumber(@Param("busId") Long busId);

    @EntityGraph(attributePaths = {"seat"})
    List<CameraOccupancy> findByBus_IdAndScanNumber(Long busId, Integer scanNumber);
}
