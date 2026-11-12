package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.service.StandingZoneTracker.Snapshot;
import com.dtc.bus_tracker.service.StandingZoneTracker.Status;

/**
 * The single AVAILABLE / NOT AVAILABLE seat indicator shown to the conductor.
 * It is derived, never stored, from two independent inputs:
 * <ul>
 *   <li>free seats on the current hop, from tickets;</li>
 *   <li>the standing area, from the camera - passengers stand there only
 *       when they cannot sit, so anyone standing means no seat is free.</li>
 * </ul>
 * The conductor never enters this: {@link StandingZoneTracker} has already
 * excluded staff from the standing count.
 */
public final class SeatAvailabilityIndicator {

    public enum Availability { AVAILABLE, NOT_AVAILABLE }

    /** Which input decided the result. */
    public enum Basis { CAMERA, TICKETS }

    public record Result(Availability status, Basis basis, String reason) {
    }

    private SeatAvailabilityIndicator() {
    }

    /**
     * @param freeSeats      free regular + priority seats on the current hop
     * @param cameraInstalled whether the bus has a standing-area camera
     * @param standing       latest standing-area reading (ignored without a camera)
     */
    public static Result derive(int freeSeats, boolean cameraInstalled, Snapshot standing) {
        boolean cameraReading = cameraInstalled && standing != null && standing.status() != Status.UNKNOWN;
        if (cameraReading && standing.status() == Status.OCCUPIED) {
            int count = standing.passengerCount();
            return new Result(Availability.NOT_AVAILABLE, Basis.CAMERA,
                    count + (count == 1 ? " passenger is" : " passengers are") + " standing in the standing area"
                            + held(standing) + ".");
        }
        if (freeSeats <= 0) {
            return new Result(Availability.NOT_AVAILABLE, Basis.TICKETS, "Every seat is ticketed on this hop.");
        }
        String seats = freeSeats + (freeSeats == 1 ? " seat free" : " seats free");
        if (cameraReading) {
            return new Result(Availability.AVAILABLE, Basis.CAMERA,
                    "Standing area is empty" + held(standing) + " · " + seats + ".");
        }
        return new Result(Availability.AVAILABLE, Basis.TICKETS, seats + " by tickets"
                + (cameraInstalled ? "; waiting for the first camera reading." : "; no camera on this bus."));
    }

    private static String held(Snapshot standing) {
        return standing.stale() ? " (last confirmed reading - camera signal lost)" : "";
    }
}
