package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.AdminStatsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminStatsHistoryTest {

    private final AdminStatsService stats = mock(AdminStatsService.class);
    private final AdminStatsHistory history = new AdminStatsHistory(stats, 30000);

    static AdminStatsResponse stats(long live) {
        return AdminStatsResponse.builder().liveBusCount(live).offlineBusCount(100 - live).delayedBusCount(live / 10)
                .averageSpeedKmh(18.5).routeStats(List.of()).build();
    }

    @Test void keepsTheLastTwoHoursOldestFirst() {
        for (int i = 0; i < AdminStatsHistory.CAPACITY + 5; i++) history.record(stats(i), i * 30_000L);
        var samples = history.history().samples();
        assertEquals(AdminStatsHistory.CAPACITY, samples.size());
        assertEquals(5, samples.get(0).live());
        assertEquals(AdminStatsHistory.CAPACITY + 4, samples.get(samples.size() - 1).live());
        assertEquals(30000, history.history().intervalMs());
    }

    @Test void copiesTheKpisIntoEachSample() {
        history.record(stats(40), 1000);
        var s = history.history().samples().get(0);
        assertEquals(new AdminStatsHistory.Sample(1000, 40, 60, 4, 18.5), s);
    }

    @Test void aFailedSampleIsSkippedNotThrown() {
        when(stats.getStats()).thenThrow(new IllegalStateException("store down"));
        history.sample();
        assertTrue(history.history().samples().isEmpty());
    }

    @Test void historyIsASnapshot() {
        history.record(stats(1), 1);
        var before = history.history().samples();
        history.record(stats(2), 2);
        assertEquals(1, before.size());
    }
}
