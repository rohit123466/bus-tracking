package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.Seat;

import java.util.List;

/**
 * Source of camera-observed seat occupancy. The only implementation today is
 * {@link SimulatedCameraOccupancyProvider}; a real computer-vision backend
 * would implement this interface (e.g. by calling an inference API with the
 * bus's latest frame) and be registered as the bean instead. Nothing else in
 * the camera module needs to change.
 *
 * Implementations report what the camera sees - they must not assume the
 * ticket data is correct, since detecting disagreement is the point.
 */
public interface CameraOccupancyProvider {

    /** Stored with every observation, e.g. "SIMULATED". */
    String sourceName();

    /** True when observations are not produced by a real vision model. */
    boolean isSimulated();

    /** CONNECTED, DISCONNECTED or NOT_INSTALLED. */
    String feedStatus(Bus bus);

    List<SeatObservation> scan(Bus bus, List<Seat> seats);

    record SeatObservation(Seat seat, CameraSeatStatus status, double confidence) {
    }
}
