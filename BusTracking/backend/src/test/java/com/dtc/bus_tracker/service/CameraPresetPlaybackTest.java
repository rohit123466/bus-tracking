package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.service.SeatAvailabilityIndicator.Availability;
import com.dtc.bus_tracker.service.StandingZoneTracker.Settings;
import com.dtc.bus_tracker.service.StandingZoneTracker.Snapshot;
import com.dtc.bus_tracker.service.StandingZoneTracker.Status;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plays the shipped preset scenarios (camera/standing-presets.json) through
 * the same provider -> tracker path the feed scheduler uses, and checks the
 * console would show the right result for each.
 */
class CameraPresetPlaybackTest {

    private final TicketingProperties properties = new TicketingProperties();
    private final CameraPresetLibrary library = new CameraPresetLibrary(properties, new DefaultResourceLoader());
    private final SimulatedCameraOccupancyProvider provider;

    CameraPresetPlaybackTest() {
        library.load();
        provider = new SimulatedCameraOccupancyProvider(null, null, null, properties, library);
    }

    /** Plays {@code ticks} feed ticks 2 s apart; returns the snapshot after each tick. */
    private List<Snapshot> play(String presetId, int ticks) {
        TicketingProperties.Camera c = properties.getCamera();
        StandingZoneTracker tracker = new StandingZoneTracker(new Settings(c.getConfirmFrames(),
                c.getReleaseFrames(), c.getMinConfidence(), Duration.ofMillis(c.getSignalTimeoutMs())));
        provider.selectPreset("BUS", presetId);
        Instant clock = Instant.parse("2026-11-12T08:00:00Z");
        List<Snapshot> out = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            clock = clock.plusMillis(c.getFeedIntervalMs());
            Instant at = clock;
            provider.nextStandingFrame("BUS").ifPresent(f -> tracker.accept(f, at));
            out.add(tracker.snapshot(clock));
        }
        return out;
    }

    private static Availability availability(Snapshot s) {
        return SeatAvailabilityIndicator.derive(10, true, s).status();
    }

    @Test
    void everyShippedPresetLoads() {
        List<String> ids = library.all().stream().map(CameraPresetLibrary.Preset::id).toList();
        assertTrue(ids.containsAll(List.of("standing-clear", "passenger-standing", "conductor-ticketing",
                "passengers-boarding", "signal-loss", "service-run")), ids.toString());
        assertEquals("service-run", library.defaultFor("DTC-101").orElseThrow().id());
        assertEquals("standing-clear", library.defaultFor("DTC-102").orElseThrow().id());
    }

    @Test
    void presetStandingClearIsAvailable() {
        Snapshot last = play("standing-clear", 5).getLast();
        assertEquals(Status.EMPTY, last.status());
        assertEquals(Availability.AVAILABLE, availability(last));
    }

    @Test
    void presetPassengerStandingIsNotAvailable() {
        Snapshot last = play("passenger-standing", 5).getLast();
        assertEquals(Status.OCCUPIED, last.status());
        assertEquals(1, last.passengerCount());
        assertEquals(Availability.NOT_AVAILABLE, availability(last));
    }

    @Test
    void presetConductorTicketingNeverCountsTheConductor() {
        List<Snapshot> run = play("conductor-ticketing", 28); // the 14-frame preset twice
        assertTrue(run.stream().allMatch(s -> s.passengerCount() == 0), "conductor must never be counted");
        assertTrue(run.stream().allMatch(s -> availability(s) == Availability.AVAILABLE));
        assertTrue(run.stream().anyMatch(Snapshot::conductorInZone));
    }

    @Test
    void presetPassengersBoardingCountsUpAndDown() {
        List<Snapshot> run = play("passengers-boarding", 26);
        int peak = run.stream().mapToInt(Snapshot::passengerCount).max().orElse(0);
        assertEquals(3, peak);
        assertEquals(0, run.getLast().passengerCount());
        assertEquals(Availability.AVAILABLE, availability(run.getLast()));
    }

    @Test
    void presetSignalLossHoldsThenRecovers() {
        List<Snapshot> run = play("signal-loss", 21);
        assertTrue(run.stream().anyMatch(s -> s.stale() && s.status() == Status.OCCUPIED),
                "while the camera is silent the last reading is held");
        assertTrue(run.stream().allMatch(s -> s.passengerCount() <= 1), "no double count after reconnecting");
        assertEquals(Status.EMPTY, run.getLast().status());
        assertFalse(run.getLast().stale());
    }

    @Test
    void presetServiceRunNeverCountsMoreThanTheRealPassengers() {
        List<Snapshot> run = play("service-run", 42);
        assertTrue(run.stream().allMatch(s -> s.passengerCount() <= 2),
                "only P1 and P2 are passengers; the conductor and repeated frames add nothing");
        assertTrue(run.stream().anyMatch(s -> s.passengerCount() == 2));
        assertTrue(run.getLast().framesIgnored() > 0, "the resent frames are rejected");
        assertEquals(Status.EMPTY, run.getLast().status());
    }

    @Test
    void pausedFeedSendsNothing() {
        provider.selectPreset("BUS", null);
        assertTrue(provider.nextStandingFrame("BUS").isEmpty());
        assertNull(provider.activePreset("BUS"));
    }

    @Test
    void unknownPresetIsRejected() {
        assertThrows(RuntimeException.class, () -> provider.selectPreset("BUS", "nope"));
    }
}
