package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.AdminStatsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Recent admin KPI samples, so the dashboard's sparklines survive a page
 * reload. In memory only: a restart starts the history again, which is fine
 * for a "last two hours" view and needs no table.
 */
@Service
@Slf4j
public class AdminStatsHistory {

    /** Two hours at the default 30 s interval. */
    static final int CAPACITY = 240;

    public record Sample(long at, long live, long offline, long slow, double averageSpeedKmh) {}

    public record History(long intervalMs, List<Sample> samples) {}

    private final AdminStatsService statsService;
    private final long intervalMs;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>(CAPACITY);

    public AdminStatsHistory(AdminStatsService statsService,
                             @Value("${admin.stats.history.interval-ms:30000}") long intervalMs) {
        this.statsService = statsService;
        this.intervalMs = intervalMs;
    }

    // The first sample waits one interval, so it is not a zero taken before the first feed poll.
    @Scheduled(fixedRateString = "${admin.stats.history.interval-ms:30000}",
            initialDelayString = "${admin.stats.history.interval-ms:30000}")
    public void sample() {
        try {
            record(statsService.getStats(), System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Admin stats sample skipped: {}", e.getMessage());
        }
    }

    synchronized void record(AdminStatsResponse stats, long atMillis) {
        if (samples.size() == CAPACITY) samples.removeFirst();
        samples.addLast(new Sample(atMillis, stats.getLiveBusCount(), stats.getOfflineBusCount(),
                stats.getDelayedBusCount(), stats.getAverageSpeedKmh()));
    }

    /** Oldest first. */
    public synchronized History history() {
        return new History(intervalMs, List.copyOf(samples));
    }
}
