package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.repository.BusLocationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusLocationRetentionServiceTest {

    private static final ZoneId DELHI = ZoneId.of("Asia/Kolkata");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T06:30:00Z"), DELHI); // 12:00 IST
    private final BusLocationRepository repository = mock(BusLocationRepository.class);

    @Test void deletesInBatchesUntilOnlyRecentRowsRemain() {
        when(repository.findMinId()).thenReturn(1L, 50_001L, 100_001L);
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 15, 12, 0);
        when(repository.deleteRecordedBefore(cutoff, 50_001L)).thenReturn(50_000);
        when(repository.deleteRecordedBefore(cutoff, 100_001L)).thenReturn(12_000);
        when(repository.deleteRecordedBefore(cutoff, 150_001L)).thenReturn(0);

        new BusLocationRetentionService(repository, 24, clock).purge();

        verify(repository).deleteRecordedBefore(cutoff, 50_001L);
        verify(repository).deleteRecordedBefore(cutoff, 100_001L);
        verify(repository).deleteRecordedBefore(cutoff, 150_001L);
    }

    @Test void stopsWhenTheTableIsEmpty() {
        when(repository.findMinId()).thenReturn(null);
        new BusLocationRetentionService(repository, 24, clock).purge();
        verify(repository, never()).deleteRecordedBefore(any(), anyLong());
    }

    @Test void zeroRetentionKeepsEverything() {
        new BusLocationRetentionService(repository, 0, clock).purge();
        verifyNoInteractions(repository);
    }
}
