package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.Seat;

import java.util.List;
import java.util.Optional;

/**
 * Source of camera-observed occupancy: per-seat scans and the standing-area
 * frame stream. The only implementation today is
 * {@link SimulatedCameraOccupancyProvider}; a real computer-vision backend
 * would implement this interface (e.g. by calling an inference API with the
 * bus's latest frame) and be registered as the bean instead. Nothing else in
 * the camera module needs to change.
 *
 * Implementations report what the camera sees - they must not assume the
 * ticket data is correct, since detecting disagreement is the point. They
 * also only <em>detect</em>: who counts as a standing passenger is decided by
 * {@link StandingZoneTracker}, and seat availability by
 * {@link SeatAvailabilityIndicator}.
 */
public interface CameraOccupancyProvider {

    /** Stored with every observation, e.g. "SIMULATED". */
    String sourceName();

    /** True when observations are not produced by a real vision model. */
    boolean isSimulated();

    /** CONNECTED, DISCONNECTED or NOT_INSTALLED. */
    String feedStatus(Bus bus);

    List<SeatObservation> scan(Bus bus, List<Seat> seats);

    /**
     * The next standing-area frame for a bus, polled by
     * {@link CameraFeedScheduler}. Empty when the camera has nothing to send
     * right now (disconnected, paused, or a device that pushes frames to the
     * API instead of being polled).
     */
    default Optional<StandingZoneTracker.Frame> nextStandingFrame(String busNumber) {
        return Optional.empty();
    }

    /** Preset scenarios this provider can play; empty for a real camera. */
    default List<FeedPreset> presets() {
        return List.of();
    }

    /** Id of the preset playing on the bus, or null when none is. */
    default String activePreset(String busNumber) {
        return null;
    }

    /** Switches the bus to a preset; null pauses the preset feed (device input only). */
    default void selectPreset(String busNumber, String presetId) {
        throw new UnsupportedOperationException("This camera source has no presets.");
    }

    /** Puts every bus back on its default preset. */
    default void resetFeed() {
    }

    record SeatObservation(Seat seat, CameraSeatStatus status, double confidence) {
    }

    record FeedPreset(String id, String label, String description) {
    }
}
