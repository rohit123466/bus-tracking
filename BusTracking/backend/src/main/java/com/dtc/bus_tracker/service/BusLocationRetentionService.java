package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.repository.BusLocationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Keeps the bus_locations history table bounded. Every live position is
 * recorded there (see BusLocationIngestService) - about 50 rows every poll,
 * several hundred thousand a day - while Journey Replay only reads the latest
 * 500 points of one bus. Without pruning the table grows until the database
 * runs out of space.
 *
 * <p>Rows are deleted in id-ordered batches so one purge never holds a
 * long-running transaction over a large backlog.
 */
@Service
@Slf4j
public class BusLocationRetentionService {

    static final int BATCH_SIZE = 50_000;

    private final BusLocationRepository repository;
    private final long retentionHours;
    private final Clock clock;

    @Autowired
    public BusLocationRetentionService(BusLocationRepository repository,
                                       @Value("${bus.history.retention-hours:24}") long retentionHours) {
        this(repository, retentionHours, Clock.systemDefaultZone());
    }

    BusLocationRetentionService(BusLocationRepository repository, long retentionHours, Clock clock) {
        this.repository = repository;
        this.retentionHours = retentionHours;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${bus.history.purge-interval-ms:3600000}", initialDelayString = "${bus.history.purge-initial-delay-ms:120000}")
    public void purge() {
        if (retentionHours <= 0) return; // retention disabled
        // recorded_at is stored in the server's zone (BusLocationIngestService), so compare in the same zone.
        LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(retentionHours);
        long total = 0;
        Long start;
        while ((start = repository.findMinId()) != null) {
            int deleted = repository.deleteRecordedBefore(cutoff, start + BATCH_SIZE);
            if (deleted == 0) break; // the oldest remaining rows are newer than the cutoff
            total += deleted;
        }
        if (total > 0) {
            log.info("Purged {} bus location history rows older than {} hours", total, retentionHours);
        }
    }
}
