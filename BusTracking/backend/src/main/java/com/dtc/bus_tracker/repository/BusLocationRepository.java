package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.BusLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BusLocationRepository extends JpaRepository<BusLocation, Long> {

    List<BusLocation> findByBus_VehicleIdOrderByRecordedAtAsc(String vehicleId);

    List<BusLocation> findTop500ByBus_VehicleIdOrderByRecordedAtDesc(String vehicleId);

    @Query("select min(b.id) from BusLocation b")
    Long findMinId();

    /** Deletes rows recorded before {@code cutoff} whose id is below {@code idBelow}, so purges run in bounded batches. */
    @Modifying
    @Transactional
    @Query("delete from BusLocation b where b.id < :idBelow and b.recordedAt < :cutoff")
    int deleteRecordedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("idBelow") long idBelow);
}
