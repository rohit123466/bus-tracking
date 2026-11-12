package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.service.SeatAvailabilityIndicator.Availability;
import com.dtc.bus_tracker.service.StandingZoneTracker.Detection;
import com.dtc.bus_tracker.service.StandingZoneTracker.Frame;
import com.dtc.bus_tracker.service.StandingZoneTracker.FrameResult;
import com.dtc.bus_tracker.service.StandingZoneTracker.Role;
import com.dtc.bus_tracker.service.StandingZoneTracker.Settings;
import com.dtc.bus_tracker.service.StandingZoneTracker.Signal;
import com.dtc.bus_tracker.service.StandingZoneTracker.Snapshot;
import com.dtc.bus_tracker.service.StandingZoneTracker.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standing-area rules for the four conductor-console scenarios and their edge
 * cases. Pure in-memory: the clock is passed in, frames are built by hand.
 * Settings mirror application.properties (confirm 2, release 3, min
 * confidence 0.5, 8 s signal timeout); frames are 2 s apart like the feed.
 */
class StandingZoneTrackerTest {

    private static final int FREE_SEATS = 12;

    private final StandingZoneTracker tracker =
            new StandingZoneTracker(new Settings(2, 3, 0.5, Duration.ofSeconds(8)));
    private Instant clock = Instant.parse("2026-11-12T08:00:00Z");
    private long sequence;

    private static Detection passenger(String id) {
        return new Detection(id, Role.PASSENGER, 0.9);
    }

    private static Detection conductor(String id) {
        return new Detection(id, Role.CONDUCTOR, 0.95);
    }

    private FrameResult frame(Detection... detections) {
        clock = clock.plusSeconds(2);
        return tracker.accept(new Frame(++sequence, clock, Arrays.asList(detections), "TEST"), clock);
    }

    private void frames(int n, Detection... detections) {
        for (int i = 0; i < n; i++) frame(detections);
    }

    private Snapshot now() {
        return tracker.snapshot(clock);
    }

    private Availability availability() {
        return SeatAvailabilityIndicator.derive(FREE_SEATS, true, now()).status();
    }

    // --- Case 1: standing area empty -------------------------------------

    @Test
    void case1_emptyStandingAreaMeansSeatsAvailable() {
        frames(2);
        Snapshot s = now();
        assertEquals(Status.EMPTY, s.status());
        assertEquals(0, s.passengerCount());
        assertEquals(Signal.CONNECTED, s.signal());
        assertEquals(Availability.AVAILABLE, availability());
    }

    @Test
    void beforeAnyFrameTheStateIsUnknownAndTicketsDecide() {
        assertEquals(Status.UNKNOWN, now().status());
        assertEquals(Signal.WAITING, now().signal());
        SeatAvailabilityIndicator.Result r = SeatAvailabilityIndicator.derive(FREE_SEATS, true, now());
        assertEquals(Availability.AVAILABLE, r.status());
        assertEquals(SeatAvailabilityIndicator.Basis.TICKETS, r.basis());
        assertEquals(Availability.NOT_AVAILABLE, SeatAvailabilityIndicator.derive(0, true, now()).status());
    }

    // --- Case 2: passenger standing --------------------------------------

    @Test
    void case2_passengerStandingMeansNotAvailable() {
        frames(2, passenger("P1"));
        Snapshot s = now();
        assertEquals(Status.OCCUPIED, s.status());
        assertEquals(1, s.passengerCount());
        assertEquals(Availability.NOT_AVAILABLE, availability());
    }

    @Test
    void passengerIsCountedOnlyAfterConsecutiveSightings() {
        frame(passenger("P1"));
        assertEquals(Status.EMPTY, now().status(), "one frame is not enough to count someone");
        assertEquals(1, now().candidates());
        frame(passenger("P1"));
        assertEquals(Status.OCCUPIED, now().status());
    }

    @Test
    void singleFalseDetectionDoesNotFlipStatus() {
        frames(2);
        frame(passenger("GHOST"));
        frame();
        assertEquals(Status.EMPTY, now().status());
        assertEquals(0, now().passengerCount());
        assertEquals(0, now().candidates());
    }

    @Test
    void lowConfidenceDetectionsAreIgnored() {
        frames(4, new Detection("P1", Role.PASSENGER, 0.3));
        assertEquals(Status.EMPTY, now().status());
    }

    @Test
    void availabilityRecoversWhenThePassengerLeaves() {
        frames(2, passenger("P1"));
        assertEquals(Availability.NOT_AVAILABLE, availability());
        frames(2);
        assertEquals(Status.OCCUPIED, now().status(), "two missed frames are held as an occlusion");
        frame();
        assertEquals(Status.EMPTY, now().status());
        assertEquals(Availability.AVAILABLE, availability());
    }

    // --- Case 3: conductor standing and selling tickets ------------------

    @Test
    void case3_conductorStandingIsNotAStandingPassenger() {
        frames(6, conductor("C1"));
        Snapshot s = now();
        assertEquals(Status.EMPTY, s.status());
        assertEquals(0, s.passengerCount());
        assertTrue(s.conductorInZone());
        assertEquals(Availability.AVAILABLE, availability());
    }

    @Test
    void conductorMovingRepeatedlyNeverCounts() {
        for (int round = 0; round < 5; round++) {
            frames(3, conductor("C1"));
            frames(3);
        }
        assertEquals(0, now().passengerCount());
        assertEquals(Status.EMPTY, now().status());
        assertFalse(now().conductorInZone());
        assertTrue(now().events().stream().noneMatch(e -> e.type().equals("STANDING_OCCUPIED")));
    }

    @Test
    void conductorMislabelledAsPassengerInALaterFrameStillDoesNotCount() {
        frames(2, conductor("C1"));
        frames(3, passenger("C1"));
        assertEquals(0, now().passengerCount());
        assertEquals(Status.EMPTY, now().status());
    }

    @Test
    void trackLaterIdentifiedAsConductorStopsCountingImmediately() {
        frames(2, passenger("C1"));
        assertEquals(1, now().passengerCount());
        frame(conductor("C1"));
        assertEquals(0, now().passengerCount());
        assertEquals(Status.EMPTY, now().status());
    }

    @Test
    void conductorNextToStandingPassengerCountsOnlyThePassenger() {
        frames(3, passenger("P1"), conductor("C1"));
        assertEquals(1, now().passengerCount());
        assertTrue(now().conductorInZone());
        assertEquals(Availability.NOT_AVAILABLE, availability());
    }

    @Test
    void ticketNoteDoesNotChangeOccupancy() {
        frames(2, conductor("C1"));
        tracker.note(clock, "TICKET_ISSUED", "Ticket #1001 issued.");
        tracker.note(clock, "TICKET_ISSUED", "Ticket #1002 issued.");
        assertEquals(0, now().passengerCount());
        assertEquals("TICKET_ISSUED", now().events().getFirst().type());
    }

    // --- Case 4: continuous verification ---------------------------------

    @Test
    void case4_multiplePassengersEnteringAndLeaving() {
        frames(2, passenger("P1"));
        assertEquals(1, now().passengerCount());
        frames(2, passenger("P1"), passenger("P2"));
        assertEquals(2, now().passengerCount());
        frames(2, passenger("P1"), passenger("P2"), passenger("P3"));
        assertEquals(3, now().passengerCount());
        frames(3, passenger("P2"), passenger("P3"));
        assertEquals(2, now().passengerCount());
        frames(3);
        assertEquals(0, now().passengerCount());
        assertEquals(Status.EMPTY, now().status());
    }

    @Test
    void samePassengerAcrossManyFramesIsCountedOnce() {
        frames(50, passenger("P1"));
        assertEquals(1, now().passengerCount());
        assertEquals(1, now().tracks().size());
    }

    @Test
    void samePassengerTwiceInOneFrameIsCountedOnce() {
        frames(3, passenger("P1"), new Detection("P1", Role.PASSENGER, 0.7));
        assertEquals(1, now().passengerCount());
    }

    @Test
    void repeatedFrameIsIgnored() {
        frame(passenger("P1"));
        Frame last = new Frame(sequence, clock, List.of(passenger("P1")), "TEST");
        assertEquals(FrameResult.DUPLICATE, tracker.accept(last, clock.plusSeconds(1)));
        assertEquals(Status.EMPTY, now().status(), "a resent frame is not a second sighting");
        assertEquals(1, now().framesIgnored());
    }

    @Test
    void lateFrameIsIgnored() {
        frames(2, passenger("P1"));
        Frame late = new Frame(sequence - 1, clock.minusSeconds(2), List.of(), "TEST");
        assertEquals(FrameResult.OUT_OF_ORDER, tracker.accept(late, clock.plusSeconds(1)));
        assertEquals(1, now().passengerCount());
    }

    @Test
    void framesWithoutSequenceAreOrderedByCaptureTime() {
        Instant t = clock.plusSeconds(2);
        assertEquals(FrameResult.APPLIED, tracker.accept(new Frame(null, t, List.of(), "DEV"), t));
        assertEquals(FrameResult.DUPLICATE, tracker.accept(new Frame(null, t, List.of(), "DEV"), t.plusSeconds(1)));
        assertEquals(FrameResult.OUT_OF_ORDER,
                tracker.accept(new Frame(null, t.minusSeconds(1), List.of(), "DEV"), t.plusSeconds(1)));
    }

    @Test
    void aNewSourceStartsItsOwnNumberingWithoutLosingPeople() {
        sequence = 900;
        frames(2, passenger("P1")); // source TEST
        Instant t = clock.plusSeconds(2);
        assertEquals(FrameResult.APPLIED,
                tracker.accept(new Frame(1L, t, List.of(passenger("P1")), "DEVICE"), t));
        assertEquals(1, now().passengerCount(), "the handover keeps the person tracked, not re-counted");
        assertEquals(FrameResult.DUPLICATE,
                tracker.accept(new Frame(1L, t, List.of(), "DEVICE"), t.plusSeconds(1)));
    }

    @Test
    void cameraDisconnectHoldsLastConfirmedState() {
        frames(2, passenger("P1"));
        clock = clock.plusSeconds(20);
        Snapshot s = now();
        assertEquals(Signal.DISCONNECTED, s.signal());
        assertTrue(s.stale());
        assertEquals(Status.OCCUPIED, s.status(), "no guessing while the camera is silent");
        assertEquals(1, s.passengerCount());
        SeatAvailabilityIndicator.Result r = SeatAvailabilityIndicator.derive(FREE_SEATS, true, s);
        assertEquals(Availability.NOT_AVAILABLE, r.status());
        assertTrue(r.reason().contains("signal lost"));
    }

    @Test
    void cameraReconnectResumesEvenIfDeviceRestartedNumbering() {
        sequence = 500;
        frames(2, passenger("P1"));
        clock = clock.plusSeconds(30);
        now(); // the console polls during the outage
        sequence = 0; // device rebooted
        frames(1, passenger("P1"));
        assertEquals(Signal.CONNECTED, now().signal());
        assertEquals(1, now().passengerCount(), "the same person is not counted twice after reconnecting");
        frames(3);
        assertEquals(Status.EMPTY, now().status());
        List<String> types = now().events().stream().map(StandingZoneTracker.Event::type).toList();
        assertTrue(types.contains("SIGNAL_LOST"));
        assertTrue(types.contains("SIGNAL_RESTORED"));
    }

    @Test
    void signalLossIsLoggedOnceEvenWithoutPolling() {
        frames(2);
        clock = clock.plusSeconds(30);
        frame();
        long lost = now().events().stream().filter(e -> e.type().equals("SIGNAL_LOST")).count();
        assertEquals(1, lost);
    }

    @Test
    void malformedDetectionsAreSkipped() {
        frames(3, new Detection(null, Role.PASSENGER, 0.9), new Detection(" ", Role.PASSENGER, 0.9), null);
        assertEquals(Status.EMPTY, now().status());
        assertEquals(0, now().passengerCount());
    }

    @Test
    void noCameraFallsBackToTickets() {
        assertEquals(Availability.AVAILABLE, SeatAvailabilityIndicator.derive(3, false, null).status());
        assertEquals(Availability.NOT_AVAILABLE, SeatAvailabilityIndicator.derive(0, false, null).status());
    }
}
