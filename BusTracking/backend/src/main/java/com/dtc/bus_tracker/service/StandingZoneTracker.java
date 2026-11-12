package com.dtc.bus_tracker.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Standing-area occupancy for one bus, built from a stream of camera frames.
 * Pure in-memory logic with the clock passed in, so it is unit-testable
 * without Spring.
 *
 * A frame lists the people the camera currently sees in the designated
 * standing area, each with the detector's track id and role. From those the
 * tracker keeps a stable passenger count:
 * <ul>
 *   <li><b>One person, one count.</b> People are keyed by track id, so the
 *       same passenger seen in many consecutive frames (or twice in one
 *       frame) is counted once.</li>
 *   <li><b>The conductor is never a passenger.</b> A track reported as
 *       CONDUCTOR is excluded, and stays excluded even if a later frame
 *       mislabels it - staff identification is sticky.</li>
 *   <li><b>Debounced.</b> A new track counts only after
 *       {@code confirmFrames} consecutive sightings, and a counted track is
 *       dropped only after {@code releaseFrames} consecutive misses, so one
 *       noisy frame or a short occlusion does not flip the status.</li>
 *   <li><b>Ordered.</b> A frame whose sequence number (or capture time) is
 *       not newer than the last applied one is ignored.</li>
 *   <li><b>Fail-safe.</b> When frames stop arriving the last confirmed state
 *       is held and reported as stale; nothing is guessed.</li>
 * </ul>
 */
public class StandingZoneTracker {

    public enum Role { PASSENGER, CONDUCTOR }

    public enum Status { EMPTY, OCCUPIED, UNKNOWN }

    public enum Signal { WAITING, CONNECTED, DISCONNECTED }

    public enum FrameResult { APPLIED, DUPLICATE, OUT_OF_ORDER }

    public record Detection(String trackId, Role role, double confidence) {
    }

    /** {@code sequence} and {@code capturedAt} are optional; at least one should be sent for ordering. */
    public record Frame(Long sequence, Instant capturedAt, List<Detection> detections, String source) {
    }

    public record Settings(int confirmFrames, int releaseFrames, double minConfidence, Duration signalTimeout) {
        public Settings {
            confirmFrames = Math.max(1, confirmFrames);
            releaseFrames = Math.max(1, releaseFrames);
        }
    }

    public record Event(Instant at, String type, String message, int passengerCount) {
    }

    public record TrackView(String trackId, Instant firstSeenAt, Instant lastSeenAt) {
    }

    public record Snapshot(Status status, int passengerCount, int candidates, boolean conductorInZone,
                           Signal signal, boolean stale, Instant lastFrameAt, Long lastSequence,
                           Instant statusChangedAt, long framesApplied, long framesIgnored, String source,
                           List<TrackView> tracks, List<Event> events) {
    }

    private static final int MAX_EVENTS = 40;
    private static final int MAX_STAFF_TRACKS = 16;

    private static final class Track {
        final Instant firstSeenAt;
        Instant lastSeenAt;
        int hits;
        int misses;
        boolean confirmed;

        Track(Instant at) {
            this.firstSeenAt = at;
            this.lastSeenAt = at;
        }
    }

    private final Settings settings;
    private final Map<String, Track> tracks = new LinkedHashMap<>();
    /** Track ids identified as staff at least once; bounded, oldest evicted first. */
    private final Set<String> staffTracks = new LinkedHashSet<>();
    private final Deque<Event> events = new ArrayDeque<>();

    private Status status = Status.UNKNOWN;
    private int passengerCount;
    private boolean conductorInZone;
    private int conductorMisses;
    private Instant lastFrameAt;
    private Instant lastCapturedAt;
    private Long lastSequence;
    private Instant statusChangedAt;
    private boolean signalLost;
    private long framesApplied;
    private long framesIgnored;
    private String source;

    public StandingZoneTracker(Settings settings) {
        this.settings = settings;
    }

    public synchronized FrameResult accept(Frame frame, Instant receivedAt) {
        boolean afterOutage = lastFrameAt != null && timedOut(receivedAt);
        // Sequence numbers belong to one source: a different camera / device
        // taking over starts its own numbering.
        boolean newSource = frame.source() != null && !frame.source().isBlank() && source != null
                && !source.equals(frame.source());
        // After an outage the device may have restarted and begun numbering
        // again, so ordering is only enforced within a continuous stream.
        if (newSource) {
            lastSequence = null;
            lastCapturedAt = null;
        } else if (!afterOutage) {
            FrameResult ordering = checkOrder(frame);
            if (ordering != FrameResult.APPLIED) {
                framesIgnored++;
                return ordering;
            }
        }
        if (signalLost || afterOutage) {
            if (!signalLost) {
                addEvent(lastFrameAt.plus(settings.signalTimeout()), "SIGNAL_LOST",
                        "Camera signal lost - holding the last confirmed reading.");
            }
            signalLost = false;
            addEvent(receivedAt, "SIGNAL_RESTORED", "Camera signal restored.");
        }

        Map<String, Detection> seen = new LinkedHashMap<>();
        boolean conductorSeen = false;
        if (frame.detections() != null) {
            for (Detection d : frame.detections()) {
                if (d == null || d.trackId() == null || d.trackId().isBlank()) continue;
                if (d.confidence() < settings.minConfidence()) continue;
                String id = d.trackId().trim();
                if (d.role() == Role.CONDUCTOR) markStaff(id);
                if (staffTracks.contains(id)) {
                    conductorSeen = true;
                    continue;
                }
                // The same person reported twice in one frame is one sighting.
                seen.merge(id, d, (a, b) -> a.confidence() >= b.confidence() ? a : b);
            }
        }
        // A counted passenger now identified as staff stops counting at once.
        tracks.keySet().removeIf(staffTracks::contains);

        for (String id : seen.keySet()) {
            Track track = tracks.computeIfAbsent(id, k -> new Track(receivedAt));
            track.hits++;
            track.misses = 0;
            track.lastSeenAt = receivedAt;
            if (track.hits >= settings.confirmFrames()) track.confirmed = true;
        }
        for (Iterator<Map.Entry<String, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Track> entry = it.next();
            if (seen.containsKey(entry.getKey())) continue;
            Track track = entry.getValue();
            track.misses++;
            // An unconfirmed candidate must be seen in consecutive frames.
            if (!track.confirmed || track.misses >= settings.releaseFrames()) it.remove();
        }

        updateConductor(conductorSeen, receivedAt);
        updateOccupancy(receivedAt);

        lastFrameAt = receivedAt;
        if (frame.sequence() != null) lastSequence = frame.sequence();
        if (frame.capturedAt() != null) lastCapturedAt = frame.capturedAt();
        if (frame.source() != null && !frame.source().isBlank()) source = frame.source();
        framesApplied++;
        return FrameResult.APPLIED;
    }

    public synchronized Snapshot snapshot(Instant now) {
        Signal signal;
        if (lastFrameAt == null) {
            signal = Signal.WAITING;
        } else if (timedOut(now)) {
            signal = Signal.DISCONNECTED;
            if (!signalLost) {
                signalLost = true;
                addEvent(lastFrameAt.plus(settings.signalTimeout()), "SIGNAL_LOST",
                        "Camera signal lost - holding the last confirmed reading.");
            }
        } else {
            signal = Signal.CONNECTED;
        }
        List<TrackView> views = new ArrayList<>();
        int candidates = 0;
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            if (e.getValue().confirmed) {
                views.add(new TrackView(e.getKey(), e.getValue().firstSeenAt, e.getValue().lastSeenAt));
            } else {
                candidates++;
            }
        }
        List<Event> recent = new ArrayList<>(events);
        return new Snapshot(status, passengerCount, candidates, conductorInZone, signal,
                signal == Signal.DISCONNECTED, lastFrameAt, lastSequence, statusChangedAt,
                framesApplied, framesIgnored, source, views, recent.reversed());
    }

    /** Adds an entry to the activity log without touching occupancy (e.g. a ticket sale). */
    public synchronized void note(Instant at, String type, String message) {
        addEvent(at, type, message);
    }

    private FrameResult checkOrder(Frame frame) {
        if (frame.sequence() != null && lastSequence != null) {
            if (frame.sequence().equals(lastSequence)) return FrameResult.DUPLICATE;
            if (frame.sequence() < lastSequence) return FrameResult.OUT_OF_ORDER;
            return FrameResult.APPLIED;
        }
        if (frame.capturedAt() != null && lastCapturedAt != null) {
            if (frame.capturedAt().equals(lastCapturedAt)) return FrameResult.DUPLICATE;
            if (frame.capturedAt().isBefore(lastCapturedAt)) return FrameResult.OUT_OF_ORDER;
        }
        return FrameResult.APPLIED;
    }

    private void markStaff(String id) {
        if (staffTracks.add(id) && staffTracks.size() > MAX_STAFF_TRACKS) {
            Iterator<String> oldest = staffTracks.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void updateConductor(boolean seen, Instant at) {
        if (seen) {
            conductorMisses = 0;
            if (!conductorInZone) {
                conductorInZone = true;
                addEvent(at, "CONDUCTOR_IN_AREA", "Conductor in the standing area - not counted as a passenger.");
            }
        } else if (conductorInZone && ++conductorMisses >= settings.releaseFrames()) {
            conductorInZone = false;
            addEvent(at, "CONDUCTOR_LEFT_AREA", "Conductor left the standing area.");
        }
    }

    private void updateOccupancy(Instant at) {
        int count = (int) tracks.values().stream().filter(t -> t.confirmed).count();
        Status next = count > 0 ? Status.OCCUPIED : Status.EMPTY;
        if (next != status) {
            statusChangedAt = at;
            addEvent(at, next == Status.OCCUPIED ? "STANDING_OCCUPIED" : "STANDING_CLEAR", next == Status.OCCUPIED
                    ? "Standing area occupied - " + plural(count) + " standing."
                    : "Standing area clear.", count);
        } else if (count != passengerCount) {
            addEvent(at, "COUNT_CHANGED", "Standing passengers: " + passengerCount + " → " + count + ".", count);
        }
        status = next;
        passengerCount = count;
    }

    private boolean timedOut(Instant now) {
        return Duration.between(lastFrameAt, now).compareTo(settings.signalTimeout()) > 0;
    }

    private void addEvent(Instant at, String type, String message) {
        addEvent(at, type, message, passengerCount);
    }

    private void addEvent(Instant at, String type, String message, int count) {
        events.addLast(new Event(at, type, message, count));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    private static String plural(int count) {
        return count + (count == 1 ? " passenger" : " passengers");
    }
}
