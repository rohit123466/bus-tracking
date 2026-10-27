package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.live.RealtimeSnapshot;
import com.google.transit.realtime.GtfsRealtime.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeedHealthServiceTest {

    private final RealtimeSnapshot snapshot = new RealtimeSnapshot();
    private final DtcIngestionService ingestion = mock(DtcIngestionService.class);
    private final FeedHealthService service = new FeedHealthService(snapshot, ingestion, 4000);

    static FeedMessage feed(long... ages) {
        long now = Instant.now().getEpochSecond();
        var b = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now));
        for (int i = 0; i < ages.length; i++) {
            b.addEntity(FeedEntity.newBuilder().setId("e" + i).setVehicle(VehiclePosition.newBuilder()
                    .setVehicle(VehicleDescriptor.newBuilder().setId("V" + i))
                    .setTrip(TripDescriptor.newBuilder().setTripId("t" + i).setRouteId("r"))
                    .setPosition(Position.newBuilder().setLatitude(28.7f).setLongitude(77.1f))
                    .setTimestamp(now - ages[i])));
        }
        return b.build();
    }

    @Test void beforeTheFirstFetchItIsUnhealthyWithNoTimes() {
        var h = service.health();
        assertNull(h.lastFetchAt());
        assertNull(h.fetchAgeSeconds());
        assertNotNull(h.feedError());
        assertFalse(h.healthy());
    }

    @Test void reportsSnapshotCountsAndLastPublish() {
        when(ingestion.lastPublishedAt()).thenReturn(1_700_000_000L);
        when(ingestion.lastPublishedCount()).thenReturn(42);
        snapshot.accept(feed(5, 10, 900), true, false);

        var h = service.health();
        assertTrue(h.healthy());
        assertEquals(3, h.vehicleCount());
        assertEquals(2, h.freshVehicleCount());
        assertTrue(h.fetchAgeSeconds() <= 1);
        assertEquals(snapshot.generation(), h.generation());
        assertEquals(1_700_000_000L, h.lastPublishAt());
        assertEquals(42, h.lastPublishCount());
        assertEquals(4000, h.pollIntervalMs());
        assertNull(h.lastError());
    }

    @Test void lastErrorOutlivesRecovery() {
        snapshot.recordFailure("DTC API returned HTTP 503; live positions unavailable.");
        var failing = service.health();
        assertFalse(failing.healthy());
        assertEquals(failing.lastError(), failing.feedError());
        assertNotNull(failing.lastErrorAt());

        snapshot.accept(feed(5), true, false);
        var recovered = service.health();
        assertTrue(recovered.healthy());
        assertNull(recovered.feedError());
        assertEquals("DTC API returned HTTP 503; live positions unavailable.", recovered.lastError());
    }
}
