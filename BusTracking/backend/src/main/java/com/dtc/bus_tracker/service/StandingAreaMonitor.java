package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.service.StandingZoneTracker.Frame;
import com.dtc.bus_tracker.service.StandingZoneTracker.FrameResult;
import com.dtc.bus_tracker.service.StandingZoneTracker.Settings;
import com.dtc.bus_tracker.service.StandingZoneTracker.Snapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live standing-area state of every camera bus: one
 * {@link StandingZoneTracker} per bus, held in memory like the live bus
 * positions. Frames arrive from the preset feed ({@link CameraFeedScheduler})
 * or from a device posting to the conductor API; both go through
 * {@link #accept}, so the same rules apply to either source.
 *
 * State is rebuilt from the next frames after a restart - until then the
 * standing area reads UNKNOWN and seat availability falls back to tickets.
 */
@Service
@RequiredArgsConstructor
public class StandingAreaMonitor {

    private final TicketingProperties properties;
    private final Map<String, StandingZoneTracker> trackers = new ConcurrentHashMap<>();

    public FrameResult accept(String busNumber, Frame frame) {
        return tracker(busNumber).accept(frame, Instant.now());
    }

    public Snapshot snapshot(String busNumber) {
        return tracker(busNumber).snapshot(Instant.now());
    }

    /** Adds an activity-log entry (e.g. a ticket sale) without affecting occupancy. */
    public void note(String busNumber, String type, String message) {
        tracker(busNumber).note(Instant.now(), type, message);
    }

    public void reset() {
        trackers.clear();
    }

    private StandingZoneTracker tracker(String busNumber) {
        return trackers.computeIfAbsent(busNumber, k -> {
            TicketingProperties.Camera c = properties.getCamera();
            return new StandingZoneTracker(new Settings(c.getConfirmFrames(), c.getReleaseFrames(),
                    c.getMinConfidence(), Duration.ofMillis(c.getSignalTimeoutMs())));
        });
    }
}
