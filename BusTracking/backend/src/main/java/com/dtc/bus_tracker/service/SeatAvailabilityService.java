package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.SeatCounts;
import com.dtc.bus_tracker.dto.SeatMapResponse;
import com.dtc.bus_tracker.dto.SeatStatus;
import com.dtc.bus_tracker.dto.SeatView;
import com.dtc.bus_tracker.dto.SegmentAvailability;
import com.dtc.bus_tracker.dto.StopProgress;
import com.dtc.bus_tracker.dto.TicketingStopView;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.SeatType;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.exception.TicketingException;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.util.RouteSegments;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Route-segment seat availability. Nothing here reads a stored "booked" flag:
 * a seat's state for a segment [from, to) is derived from the trip's tickets,
 * where a ticket holds its seat only for [fromSequence, occupiedUntil). A
 * seat can therefore be taken A&rarr;C and free again C&rarr;F.
 */
@Service
@RequiredArgsConstructor
public class SeatAvailabilityService {

    private final TicketingJourneyLoader journeyLoader;
    private final FareCalculator fareCalculator;
    private final StopRepository stopRepository;

    /** One seat's state for a requested segment and passenger category. */
    public record SeatState(Seat seat, SeatStatus status, boolean selectable, String note,
                            Integer occupiedFrom, Integer occupiedTo, Integer vacatedAt,
                            List<Ticket> blockingTickets) {
    }

    /** A validated segment: stop sequences plus their positions in the journey. */
    public record Segment(StopTime from, StopTime to) {
        public int fromSequence() {
            return from.getStopSequence();
        }

        public int toSequence() {
            return to.getStopSequence();
        }
    }

    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(String busNumber, Long fromStopId, Long toStopId, PassengerCategory category) {
        TicketingJourney journey = journeyLoader.load(busNumber);
        PassengerCategory effectiveCategory = category != null ? category : PassengerCategory.ADULT;
        Segment segment = resolveSegment(journey, fromStopId, toStopId);

        List<SeatState> states = seatStates(journey, segment.fromSequence(), segment.toSequence(), effectiveCategory);
        SeatCounts counts = count(journey, states);
        boolean soldOut = counts.getAvailableForCategory() == 0;
        int stopsTravelled = journey.indexOf(segment.toSequence()) - journey.indexOf(segment.fromSequence());

        return SeatMapResponse.builder()
                .busNumber(journey.busNumber())
                .busType(journey.bus().getBusType())
                .busTypeLabel(journey.bus().getBusType().getLabel())
                .routeCode(journey.routeCode())
                .routeName(journey.trip().getRoute().getName())
                .stops(stopViews(journey))
                .fromStop(stopView(journey, segment.from()))
                .toStop(stopView(journey, segment.to()))
                .category(effectiveCategory)
                .categoryLabel(effectiveCategory.getLabel())
                .layoutRows(journey.seats().stream().mapToInt(s -> s.getRowIndex() + s.getRowSpan()).max().orElse(0))
                .layoutColumns(SeatLayoutFactory.COLUMNS)
                .aisleColumn(SeatLayoutFactory.AISLE_COLUMN)
                .seats(states.stream().map(s -> toView(journey, s)).toList())
                .counts(counts)
                .soldOut(soldOut)
                .soldOutMessage(soldOut ? soldOutMessage(journey, segment, effectiveCategory, counts) : null)
                .fare(fareCalculator.quote(effectiveCategory, SeatType.REGULAR, stopsTravelled))
                .build();
    }

    /**
     * Validates a boarding/destination pair against the journey's stop
     * sequence. Missing ids default to "from the bus's current stop to the
     * next one", i.e. the seat map as of right now.
     */
    public Segment resolveSegment(TicketingJourney journey, Long fromStopId, Long toStopId) {
        StopTime from = fromStopId == null
                ? journey.stopAt(journey.currentSequence()).orElse(journey.stops().getFirst())
                : requireStopOnRoute(journey, fromStopId, "Boarding");
        StopTime to;
        if (toStopId == null) {
            int next = Math.min(journey.indexOf(from.getStopSequence()) + 1, journey.stops().size() - 1);
            to = journey.stops().get(next);
        } else {
            to = requireStopOnRoute(journey, toStopId, "Destination");
        }

        if (from.getStopSequence().equals(to.getStopSequence())) {
            throw TicketingException.badRequest("Boarding and destination stop are both "
                    + from.getStop().getName() + " - pick a destination further along the route.");
        }
        if (to.getStopSequence() < from.getStopSequence()) {
            throw TicketingException.badRequest("Destination " + to.getStop().getName()
                    + " comes before boarding stop " + from.getStop().getName() + " on route "
                    + journey.routeCode() + ". Choose a destination after the boarding stop.");
        }
        if (from.getStopSequence() < journey.currentSequence()) {
            throw TicketingException.badRequest("Bus " + journey.busNumber() + " has already left "
                    + from.getStop().getName() + " (it is now at " + journey.stopName(journey.currentSequence())
                    + "). Choose a boarding stop from there onwards.");
        }
        return new Segment(from, to);
    }

    private StopTime requireStopOnRoute(TicketingJourney journey, Long stopId, String role) {
        return journey.findStop(stopId).orElseThrow(() -> {
            String label = stopRepository.findById(stopId).map(s -> s.getName()).orElse(null);
            return TicketingException.badRequest(label == null
                    ? role + " stop #" + stopId + " does not exist."
                    : role + " stop " + label + " is not on route " + journey.routeCode()
                    + " of bus " + journey.busNumber() + ".");
        });
    }

    /** State of every seat of the bus for [from, to), from the point of view of {@code category}. */
    public List<SeatState> seatStates(TicketingJourney journey, int from, int to, PassengerCategory category) {
        Map<Long, List<Ticket>> ticketsBySeat = new HashMap<>();
        for (Ticket t : journey.tickets()) {
            if (t.occupiedUntilSequence() != null) {
                ticketsBySeat.computeIfAbsent(t.getSeat().getId(), k -> new ArrayList<>()).add(t);
            }
        }

        List<SeatState> states = new ArrayList<>();
        for (Seat seat : journey.seats()) {
            List<Ticket> holders = ticketsBySeat.getOrDefault(seat.getId(), List.of());
            states.add(stateOf(journey, seat, holders, from, to, category));
        }
        return states;
    }

    private SeatState stateOf(TicketingJourney journey, Seat seat, List<Ticket> holders, int from, int to,
                              PassengerCategory category) {
        if (!Boolean.TRUE.equals(seat.getInService())) {
            return new SeatState(seat, SeatStatus.UNAVAILABLE, false, "Out of service", null, null, null, List.of());
        }

        List<Ticket> conflicts = holders.stream()
                .filter(t -> RouteSegments.overlaps(from, to, t.getFromSequence(), t.occupiedUntilSequence()))
                .toList();
        if (!conflicts.isEmpty()) {
            int occupiedFrom = conflicts.stream().mapToInt(t -> Math.max(from, t.getFromSequence())).min().orElse(from);
            int occupiedTo = conflicts.stream().mapToInt(t -> Math.min(to, t.occupiedUntilSequence())).max().orElse(to);
            return new SeatState(seat, SeatStatus.OCCUPIED, false,
                    "Occupied from " + journey.stopName(occupiedFrom) + " to " + journey.stopName(occupiedTo),
                    occupiedFrom, occupiedTo, null, conflicts);
        }

        // Free for the whole segment. If an earlier passenger's journey ended
        // at or before our boarding stop, the seat was vacated there.
        Integer vacatedAt = holders.stream()
                .map(Ticket::occupiedUntilSequence)
                .filter(until -> until <= from)
                .max(Integer::compare)
                .orElse(null);

        boolean selectable = category.canUse(seat.getSeatType());
        SeatStatus status = switch (seat.getSeatType()) {
            case WHEELCHAIR -> SeatStatus.WHEELCHAIR;
            case PRIORITY -> SeatStatus.RESERVED;
            case REGULAR -> vacatedAt != null ? SeatStatus.VACATED : SeatStatus.AVAILABLE;
        };
        String note;
        if (!selectable) {
            note = restrictionNote(seat.getSeatType());
        } else if (vacatedAt != null) {
            note = "Vacated at " + journey.stopName(vacatedAt) + " - free again from there";
        } else {
            note = null;
        }
        return new SeatState(seat, status, selectable, note, null, null, vacatedAt, List.of());
    }

    public static String restrictionNote(SeatType seatType) {
        return switch (seatType) {
            case PRIORITY -> "Priority seat - Senior Citizen and Divyangjan tickets only";
            case WHEELCHAIR -> "Wheelchair space - Person with Disability / Divyangjan tickets only";
            case REGULAR -> null;
        };
    }

    public SeatCounts count(TicketingJourney journey, List<SeatState> states) {
        int availableRegular = 0, availablePriority = 0, availableWheelchair = 0;
        int occupiedSeats = 0, occupiedWheelchair = 0, unavailable = 0, availableForCategory = 0;
        for (SeatState s : states) {
            boolean wheelchair = s.seat().isWheelchairSpace();
            switch (s.status()) {
                case UNAVAILABLE -> unavailable++;
                case OCCUPIED -> {
                    if (wheelchair) occupiedWheelchair++;
                    else occupiedSeats++;
                }
                default -> {
                    switch (s.seat().getSeatType()) {
                        case REGULAR -> availableRegular++;
                        case PRIORITY -> availablePriority++;
                        case WHEELCHAIR -> availableWheelchair++;
                    }
                    if (s.selectable()) availableForCategory++;
                }
            }
        }
        return SeatCounts.builder()
                .totalSeats((int) journey.seats().stream().filter(s -> !s.isWheelchairSpace()).count())
                .prioritySeats((int) journey.seats().stream().filter(s -> s.getSeatType() == SeatType.PRIORITY).count())
                .wheelchairSpaces((int) journey.seats().stream().filter(Seat::isWheelchairSpace).count())
                .availableRegular(availableRegular)
                .availablePriority(availablePriority)
                .availableWheelchair(availableWheelchair)
                .occupiedSeats(occupiedSeats)
                .occupiedWheelchair(occupiedWheelchair)
                .unavailableSeats(unavailable)
                .availableForCategory(availableForCategory)
                .build();
    }

    public String soldOutMessage(TicketingJourney journey, Segment segment, PassengerCategory category, SeatCounts counts) {
        String base = "Sold Out for this route segment (" + segment.from().getStop().getName() + " → "
                + segment.to().getStop().getName() + ")";
        int anyFree = counts.getAvailableRegular() + counts.getAvailablePriority() + counts.getAvailableWheelchair();
        return anyFree == 0 ? base + "." : base + ": no places left that a " + category.getLabel() + " ticket can use.";
    }

    /** Free places on each stop-to-stop hop of the journey. */
    public List<SegmentAvailability> hops(TicketingJourney journey) {
        List<SegmentAvailability> hops = new ArrayList<>();
        List<StopTime> stops = journey.stops();
        for (int i = 0; i + 1 < stops.size(); i++) {
            int from = stops.get(i).getStopSequence();
            int to = stops.get(i + 1).getStopSequence();
            SeatCounts c = count(journey, seatStates(journey, from, to, PassengerCategory.DIVYANGJAN));
            int seatsFree = c.getAvailableRegular() + c.getAvailablePriority();
            hops.add(SegmentAvailability.builder()
                    .fromStop(stops.get(i).getStop().getName())
                    .toStop(stops.get(i + 1).getStop().getName())
                    .fromSequence(from)
                    .toSequence(to)
                    .availableSeats(seatsFree)
                    .availablePriority(c.getAvailablePriority())
                    .availableWheelchair(c.getAvailableWheelchair())
                    .occupiedSeats(c.getOccupiedSeats())
                    .soldOut(seatsFree + c.getAvailableWheelchair() == 0)
                    .build());
        }
        return hops;
    }

    /** Seat id -> the ticket of the passenger sitting there while the bus is at {@code sequence}. */
    public Map<Long, Ticket> onBoardAt(TicketingJourney journey, int sequence) {
        Map<Long, Ticket> onBoard = new HashMap<>();
        for (Ticket t : journey.tickets()) {
            Integer until = t.occupiedUntilSequence();
            if (until != null && RouteSegments.onBoardAt(t.getFromSequence(), until, sequence)) {
                onBoard.put(t.getSeat().getId(), t);
            }
        }
        return onBoard;
    }

    public List<TicketingStopView> stopViews(TicketingJourney journey) {
        return journey.stops().stream().map(st -> stopView(journey, st)).toList();
    }

    public TicketingStopView stopView(TicketingJourney journey, StopTime st) {
        int current = journey.currentSequence();
        int seq = st.getStopSequence();
        StopProgress progress;
        if (seq < current) progress = StopProgress.PASSED;
        else if (seq == current) progress = StopProgress.CURRENT;
        else if (journey.indexOf(seq) == journey.indexOf(current) + 1) progress = StopProgress.NEXT;
        else progress = StopProgress.UPCOMING;
        return TicketingStopView.builder()
                .stopId(st.getStop().getId())
                .name(st.getStop().getName())
                .sequence(seq)
                .progress(progress)
                .build();
    }

    private SeatView toView(TicketingJourney journey, SeatState s) {
        Seat seat = s.seat();
        return SeatView.builder()
                .seatId(seat.getId())
                .seatNumber(seat.getSeatNumber())
                .seatType(seat.getSeatType())
                .row(seat.getRowIndex())
                .column(seat.getColumnIndex())
                .rowSpan(seat.getRowSpan())
                .columnSpan(seat.getColumnSpan())
                .status(s.status())
                .selectable(s.selectable())
                .note(s.note())
                .occupiedFromStop(s.occupiedFrom() != null ? journey.stopName(s.occupiedFrom()) : null)
                .occupiedToStop(s.occupiedTo() != null ? journey.stopName(s.occupiedTo()) : null)
                .vacatedAtStop(s.vacatedAt() != null ? journey.stopName(s.vacatedAt()) : null)
                .build();
    }
}
