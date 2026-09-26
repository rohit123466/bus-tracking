package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.SeatStatus;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.SeatType;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.entity.TicketStatus;
import com.dtc.bus_tracker.entity.Trip;
import com.dtc.bus_tracker.service.SeatAvailabilityService.SeatState;
import com.dtc.bus_tracker.util.RouteSegments;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Route-segment occupancy on route A(1) -> B(2) -> C(3) -> D(4) -> E(5) -> F(6),
 * mirroring the acceptance scenarios. Pure in-memory, no Spring context.
 */
class SeatAvailabilityServiceTest {

    private static final int A = 1, B = 2, C = 3, D = 4, E = 5, F = 6;

    private final SeatAvailabilityService service = new SeatAvailabilityService(null, null, null);
    private final Seat seat05 = seat(5L, "05", SeatType.REGULAR);
    private final Seat seat06 = seat(6L, "06", SeatType.REGULAR);
    private final Seat priority01 = seat(1L, "01", SeatType.PRIORITY);
    private final Seat wheelchair = seat(99L, "WC1", SeatType.WHEELCHAIR);

    @Test
    void overlapUsesHalfOpenSequenceRanges() {
        assertTrue(RouteSegments.overlaps(B, D, A, C));   // B->D vs A->C share B->C
        assertTrue(RouteSegments.overlaps(A, B, A, C));
        assertTrue(RouteSegments.overlaps(B, C, A, C));
        assertTrue(RouteSegments.overlaps(A, C, A, C));
        assertFalse(RouteSegments.overlaps(C, D, A, C));  // touching at C is not overlapping
        assertFalse(RouteSegments.overlaps(C, E, A, C));
        assertFalse(RouteSegments.overlaps(D, F, A, C));
    }

    @Test
    void seatTakenAtoCIsOccupiedOnlyUntilC() {
        TicketingJourney journey = journey(List.of(seat05), List.of(ticket(seat05, A, C)));

        SeatState bToD = state(journey, seat05, B, D, PassengerCategory.ADULT);
        assertEquals(SeatStatus.OCCUPIED, bToD.status());
        assertFalse(bToD.selectable());
        assertEquals("Occupied from B to C", bToD.note());

        for (int[] seg : new int[][]{{A, B}, {B, C}, {A, C}}) {
            assertEquals(SeatStatus.OCCUPIED, state(journey, seat05, seg[0], seg[1], PassengerCategory.ADULT).status());
        }
        for (int[] seg : new int[][]{{C, D}, {C, E}, {D, F}}) {
            SeatState s = state(journey, seat05, seg[0], seg[1], PassengerCategory.ADULT);
            assertEquals(SeatStatus.VACATED, s.status());
            assertTrue(s.selectable());
            assertEquals(C, s.vacatedAt());
        }
    }

    @Test
    void secondPassengerCtoEBlocksDtoFButNotAtoC() {
        TicketingJourney journey = journey(List.of(seat05), List.of(ticket(seat05, A, C), ticket(seat05, C, E)));
        SeatState dToF = state(journey, seat05, D, F, PassengerCategory.ADULT);
        assertEquals(SeatStatus.OCCUPIED, dToF.status());
        assertEquals("Occupied from D to E", dToF.note());
        assertEquals(SeatStatus.VACATED, state(journey, seat05, E, F, PassengerCategory.ADULT).status());
    }

    @Test
    void earlyAlightingFreesSeatFromWherePassengerGotDown() {
        Ticket t = ticket(seat05, A, E);
        t.setStatus(TicketStatus.COMPLETED);
        t.setAlightedSequence(C);
        TicketingJourney journey = journey(List.of(seat05), List.of(t));
        assertEquals(SeatStatus.OCCUPIED, state(journey, seat05, B, C, PassengerCategory.ADULT).status());
        assertEquals(SeatStatus.VACATED, state(journey, seat05, C, E, PassengerCategory.ADULT).status());
    }

    @Test
    void cancelledTicketHoldsNothing() {
        Ticket t = ticket(seat05, A, F);
        t.setStatus(TicketStatus.CANCELLED);
        TicketingJourney journey = journey(List.of(seat05), List.of(t));
        assertEquals(SeatStatus.AVAILABLE, state(journey, seat05, A, F, PassengerCategory.ADULT).status());
    }

    @Test
    void wheelchairSpaceOnlySelectableForDivyangjan() {
        TicketingJourney journey = journey(List.of(wheelchair, priority01), List.of());
        SeatState adult = state(journey, wheelchair, A, D, PassengerCategory.ADULT);
        assertEquals(SeatStatus.WHEELCHAIR, adult.status());
        assertFalse(adult.selectable());
        assertFalse(state(journey, wheelchair, A, D, PassengerCategory.SENIOR_CITIZEN).selectable());
        assertTrue(state(journey, wheelchair, A, D, PassengerCategory.DIVYANGJAN).selectable());

        assertEquals(SeatStatus.RESERVED, state(journey, priority01, A, D, PassengerCategory.ADULT).status());
        assertFalse(state(journey, priority01, A, D, PassengerCategory.ADULT).selectable());
        assertTrue(state(journey, priority01, A, D, PassengerCategory.SENIOR_CITIZEN).selectable());
        assertTrue(state(journey, priority01, A, D, PassengerCategory.DIVYANGJAN).selectable());
    }

    @Test
    void soldOutIsPerSegment() {
        TicketingJourney journey = journey(List.of(seat05, seat06),
                List.of(ticket(seat05, A, C), ticket(seat06, A, C)));
        assertEquals(0, service.count(journey, service.seatStates(journey, A, C, PassengerCategory.ADULT))
                .getAvailableForCategory());
        assertEquals(2, service.count(journey, service.seatStates(journey, C, E, PassengerCategory.ADULT))
                .getAvailableForCategory());
        assertEquals(SeatStatus.VACATED, state(journey, seat05, C, E, PassengerCategory.ADULT).status());
    }

    @Test
    void outOfServiceSeatIsUnavailable() {
        Seat broken = seat(7L, "07", SeatType.REGULAR);
        broken.setInService(false);
        TicketingJourney journey = journey(List.of(broken), List.of());
        SeatState s = state(journey, broken, A, B, PassengerCategory.ADULT);
        assertEquals(SeatStatus.UNAVAILABLE, s.status());
        assertFalse(s.selectable());
    }

    @Test
    void onBoardAtCurrentStop() {
        TicketingJourney journey = journey(List.of(seat05, seat06),
                List.of(ticket(seat05, A, C), ticket(seat06, C, E)));
        assertEquals(List.of(5L), List.copyOf(service.onBoardAt(journey, B).keySet()));
        assertEquals(List.of(6L), List.copyOf(service.onBoardAt(journey, C).keySet()));
        assertTrue(service.onBoardAt(journey, F).isEmpty());
    }

    private SeatState state(TicketingJourney journey, Seat seat, int from, int to, PassengerCategory category) {
        return service.seatStates(journey, from, to, category).stream()
                .filter(s -> s.seat() == seat)
                .findFirst()
                .orElseThrow();
    }

    private static TicketingJourney journey(List<Seat> seats, List<Ticket> tickets) {
        Route route = Route.builder().id(1L).routeCode("T").name("Test").build();
        Trip trip = Trip.builder().id(1L).route(route).currentStopSequence(A).build();
        List<StopTime> stops = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Stop stop = Stop.builder().id(100L + i).stopId("S" + i).name(String.valueOf((char) ('A' + i)))
                    .latitude(0.0).longitude(0.0).build();
            stops.add(StopTime.builder().stop(stop).stopSequence(i + 1).build());
        }
        return new TicketingJourney(Bus.builder().id(1L).vehicleId("TEST-1").build(), trip, stops, seats, tickets);
    }

    private static Seat seat(Long id, String number, SeatType type) {
        return Seat.builder().id(id).seatNumber(number).seatType(type)
                .rowIndex(0).columnIndex(0).rowSpan(1).columnSpan(1).inService(true).build();
    }

    private static Ticket ticket(Seat seat, int from, int to) {
        return Ticket.builder().seat(seat).fromSequence(from).toSequence(to).status(TicketStatus.ACTIVE).build();
    }
}
