package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.dto.FareQuote;
import com.dtc.bus_tracker.dto.PassengerDto;
import com.dtc.bus_tracker.dto.SeatCounts;
import com.dtc.bus_tracker.dto.SeatStatus;
import com.dtc.bus_tracker.dto.SellTicketRequest;
import com.dtc.bus_tracker.dto.TicketResponse;
import com.dtc.bus_tracker.entity.Passenger;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.SeatType;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.entity.TicketStatus;
import com.dtc.bus_tracker.exception.ResourceNotFoundException;
import com.dtc.bus_tracker.exception.TicketingException;
import com.dtc.bus_tracker.repository.PassengerRepository;
import com.dtc.bus_tracker.repository.TicketRepository;
import com.dtc.bus_tracker.service.SeatAvailabilityService.Segment;
import com.dtc.bus_tracker.service.SeatAvailabilityService.SeatState;
import com.dtc.bus_tracker.util.RouteSegments;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conductor ticketing: selling a seat for a route segment, completing a
 * passenger's journey when they get down, cancelling, and moving the bus to
 * its next stop. Every write locks the trip row first (see
 * {@link TicketingJourneyLoader#loadForUpdate}) so two sales can never both
 * take the same seat.
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    private static final long TICKET_NUMBER_OFFSET = 1000;

    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final FareCalculator fareCalculator;
    private final TicketRepository ticketRepository;
    private final PassengerRepository passengerRepository;
    private final TicketingProperties properties;

    @Transactional
    public TicketResponse sell(SellTicketRequest request, String issuedBy) {
        String requestId = blankToNull(request.getRequestId());
        rejectDuplicateRequest(requestId);

        TicketingJourney journey = journeyLoader.loadForUpdate(request.getBusNumber());
        // Re-check under the trip lock: a concurrent duplicate is visible now.
        rejectDuplicateRequest(requestId);

        Passenger passenger = resolvePassenger(request);
        PassengerCategory category = passenger.getCategory();
        Segment segment = seatAvailability.resolveSegment(journey, request.getFromStopId(), request.getToStopId());
        int from = segment.fromSequence();
        int to = segment.toSequence();

        Seat requestedSeat = blankToNull(request.getSeatNumber()) == null ? null : findSeat(journey, request.getSeatNumber());
        if (requestedSeat != null) {
            if (!Boolean.TRUE.equals(requestedSeat.getInService())) {
                throw TicketingException.conflict(label(requestedSeat) + " is out of service and cannot be sold.");
            }
            if (!category.canUse(requestedSeat.getSeatType())) {
                throw new TicketingException(HttpStatus.FORBIDDEN, requestedSeat.isWheelchairSpace()
                        ? "♿ Wheelchair space " + requestedSeat.getSeatNumber() + " can only be booked with a "
                          + PassengerCategory.DIVYANGJAN.getLabel() + " ticket. " + passenger.getName()
                          + " is travelling as " + category.getLabel() + "."
                        : "Seat " + requestedSeat.getSeatNumber() + " is a priority seat for Senior Citizen and "
                          + "Divyangjan passengers; " + passenger.getName() + " is travelling as "
                          + category.getLabel() + ".");
            }
        }

        rejectOverlappingTicketForPassenger(journey, passenger, from, to);

        List<SeatState> states = seatAvailability.seatStates(journey, from, to, category);
        SeatCounts counts = seatAvailability.count(journey, states);
        if (counts.getAvailableForCategory() == 0) {
            throw TicketingException.conflict(seatAvailability.soldOutMessage(journey, segment, category, counts));
        }
        enforceCapacity(journey, segment);

        Seat seat = requestedSeat != null ? requestedSeat : pickSeat(states, category);
        SeatState seatState = states.stream().filter(s -> s.seat().getId().equals(seat.getId())).findFirst().orElseThrow();
        if (seatState.status() == SeatStatus.OCCUPIED) {
            throw TicketingException.conflict(label(seat) + " is already occupied from "
                    + journey.stopName(seatState.occupiedFrom()) + " to " + journey.stopName(seatState.occupiedTo())
                    + ". Choose another seat, or a segment starting at " + journey.stopName(seatState.occupiedTo())
                    + " or later.");
        }

        int stopsTravelled = journey.indexOf(to) - journey.indexOf(from);
        FareQuote fare = fareCalculator.quote(category, seat.getSeatType(), stopsTravelled);

        Ticket ticket = ticketRepository.save(Ticket.builder()
                .requestId(requestId)
                .bus(journey.bus())
                .trip(journey.trip())
                .seat(seat)
                .passenger(passenger)
                .fromStop(segment.from().getStop())
                .toStop(segment.to().getStop())
                .fromSequence(from)
                .toSequence(to)
                .passengerCategory(category)
                .ticketType(fare.getTicketType())
                .fare(fare.getAmount())
                .status(TicketStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .issuedBy(issuedBy)
                .build());
        ticket.setTicketNumber(String.valueOf(TICKET_NUMBER_OFFSET + ticket.getId()));
        return toResponse(journey, ticket);
    }

    /**
     * Passenger got down. Frees the seat from where they actually alighted:
     * {@code atStopId} if given, else the bus's current stop (capped at the
     * ticket's destination).
     */
    @Transactional
    public TicketResponse complete(String ticketNumber, Long atStopId) {
        Ticket ticket = findTicket(ticketNumber);
        TicketingJourney journey = journeyLoader.loadForUpdate(ticket.getBus().getVehicleId());
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            throw TicketingException.conflict("Ticket #" + ticketNumber + " was cancelled; there is no journey to complete.");
        }
        if (ticket.getStatus() == TicketStatus.COMPLETED) {
            throw TicketingException.conflict("Ticket #" + ticketNumber + " is already completed (passenger got down at "
                    + journey.stopName(ticket.occupiedUntilSequence()) + ").");
        }

        int alightAt;
        if (atStopId != null) {
            StopTime stop = journey.findStop(atStopId).orElseThrow(() ->
                    TicketingException.badRequest("Stop #" + atStopId + " is not on route " + journey.routeCode() + "."));
            alightAt = stop.getStopSequence();
            if (alightAt > ticket.getToSequence()) {
                throw TicketingException.badRequest("Ticket #" + ticketNumber + " is only valid up to "
                        + journey.stopName(ticket.getToSequence()) + ".");
            }
        } else {
            alightAt = Math.min(journey.currentSequence(), ticket.getToSequence());
        }
        if (alightAt <= ticket.getFromSequence()) {
            throw TicketingException.conflict("Passenger on ticket #" + ticketNumber + " boards at "
                    + journey.stopName(ticket.getFromSequence()) + " and has not travelled yet (bus is at "
                    + journey.stopName(journey.currentSequence()) + "). Cancel the ticket instead.");
        }

        ticket.setStatus(TicketStatus.COMPLETED);
        ticket.setAlightedSequence(alightAt);
        ticket.setCompletedAt(LocalDateTime.now());
        return toResponse(journey, ticket);
    }

    @Transactional
    public TicketResponse cancel(String ticketNumber) {
        Ticket ticket = findTicket(ticketNumber);
        TicketingJourney journey = journeyLoader.loadForUpdate(ticket.getBus().getVehicleId());
        if (ticket.getStatus() != TicketStatus.ACTIVE) {
            throw TicketingException.conflict("Only active tickets can be cancelled; ticket #" + ticketNumber
                    + " is " + ticket.getStatus().name().toLowerCase() + ".");
        }
        ticket.setStatus(TicketStatus.CANCELLED);
        ticket.setCompletedAt(LocalDateTime.now());
        return toResponse(journey, ticket);
    }

    /**
     * Moves the bus to its next stop. Passengers whose destination is that
     * stop get down there: their tickets complete and the seats free up for
     * the rest of the route. Returns the tickets completed by this move.
     */
    @Transactional
    public List<TicketResponse> advance(String busNumber) {
        TicketingJourney journey = journeyLoader.loadForUpdate(busNumber);
        int current = journey.currentSequence();
        if (current == journey.lastSequence()) {
            throw TicketingException.conflict("Bus " + busNumber + " is already at its final stop ("
                    + journey.stopName(current) + "). Reset the demo to start a new journey.");
        }
        int next = journey.stops().get(journey.indexOf(current) + 1).getStopSequence();
        journey.trip().setCurrentStopSequence(next);

        List<TicketResponse> alighted = new ArrayList<>();
        for (Ticket t : journey.tickets()) {
            if (t.getStatus() == TicketStatus.ACTIVE && t.getToSequence() <= next) {
                t.setStatus(TicketStatus.COMPLETED);
                t.setAlightedSequence(t.getToSequence());
                t.setCompletedAt(LocalDateTime.now());
                alighted.add(toResponse(journey, t));
            }
        }
        return alighted;
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> ticketsForBus(String busNumber) {
        TicketingJourney journey = journeyLoader.load(busNumber);
        return journey.tickets().stream().map(t -> toResponse(journey, t)).toList();
    }

    @Transactional(readOnly = true)
    public List<PassengerDto> passengers() {
        return passengerRepository.findAllByOrderByNameAsc().stream()
                .map(p -> PassengerDto.builder()
                        .id(p.getId())
                        .name(p.getName())
                        .category(p.getCategory())
                        .categoryLabel(p.getCategory().getLabel())
                        .build())
                .toList();
    }

    private void rejectDuplicateRequest(String requestId) {
        if (requestId == null) return;
        ticketRepository.findByRequestId(requestId).ifPresent(existing -> {
            throw TicketingException.conflict("Duplicate ticket request: this request was already issued as ticket #"
                    + existing.getTicketNumber() + ".");
        });
    }

    private Passenger resolvePassenger(SellTicketRequest request) {
        if (request.getPassengerId() != null) {
            Passenger passenger = passengerRepository.findById(request.getPassengerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Passenger #" + request.getPassengerId() + " does not exist."));
            if (request.getCategory() != null && request.getCategory() != passenger.getCategory()) {
                throw TicketingException.badRequest(passenger.getName() + " is registered as "
                        + passenger.getCategory().getLabel() + ", so a " + request.getCategory().getLabel()
                        + " ticket cannot be issued to them.");
            }
            return passenger;
        }

        String name = blankToNull(request.getPassengerName());
        if (name == null) {
            throw TicketingException.badRequest("Select an existing passenger or enter a new passenger's name.");
        }
        if (request.getCategory() == null) {
            throw TicketingException.badRequest("Select a passenger category for " + name + ".");
        }
        return passengerRepository.save(Passenger.builder()
                .name(name)
                .category(request.getCategory())
                .createdAt(LocalDateTime.now())
                .build());
    }

    private Seat findSeat(TicketingJourney journey, String rawSeatNumber) {
        String number = normalizeSeatNumber(rawSeatNumber);
        return journey.seats().stream()
                .filter(s -> s.getSeatNumber().equalsIgnoreCase(number))
                .findFirst()
                .orElseThrow(() -> TicketingException.badRequest("Seat " + rawSeatNumber.trim()
                        + " does not belong to bus " + journey.busNumber() + "."));
    }

    /** "5" -> "05", "wc1" -> "WC1". */
    static String normalizeSeatNumber(String raw) {
        String trimmed = raw.trim().toUpperCase();
        return trimmed.matches("\\d") ? "0" + trimmed : trimmed;
    }

    private void rejectOverlappingTicketForPassenger(TicketingJourney journey, Passenger passenger, int from, int to) {
        if (passenger.getId() == null) return;
        journey.tickets().stream()
                .filter(t -> t.getPassenger().getId().equals(passenger.getId()))
                .filter(t -> t.occupiedUntilSequence() != null)
                .filter(t -> RouteSegments.overlaps(from, to, t.getFromSequence(), t.occupiedUntilSequence()))
                .findFirst()
                .ifPresent(t -> {
                    throw TicketingException.conflict(passenger.getName() + " already holds ticket #" + t.getTicketNumber()
                            + " on bus " + journey.busNumber() + " for an overlapping journey ("
                            + journey.stopName(t.getFromSequence()) + " → " + journey.stopName(t.getToSequence()) + ").");
                });
    }

    /**
     * Hard ceiling: on no hop of the requested segment may the number of
     * ticketed passengers reach the bus's in-service capacity (seats plus
     * wheelchair spaces). Per-seat checks already imply this; it is enforced
     * separately so capacity is guaranteed even if seat data were inconsistent.
     */
    private void enforceCapacity(TicketingJourney journey, Segment segment) {
        long capacity = journey.seats().stream().filter(s -> Boolean.TRUE.equals(s.getInService())).count();
        int fromIndex = journey.indexOf(segment.fromSequence());
        int toIndex = journey.indexOf(segment.toSequence());
        for (int i = fromIndex; i < toIndex; i++) {
            int hopStart = journey.stops().get(i).getStopSequence();
            long load = seatAvailability.onBoardAt(journey, hopStart).size();
            if (load >= capacity) {
                throw TicketingException.conflict("Bus " + journey.busNumber() + " is at full capacity ("
                        + load + " of " + capacity + " places) between " + journey.stopName(hopStart) + " and "
                        + journey.stops().get(i + 1).getStop().getName() + ". Sold Out for this route segment.");
            }
        }
    }

    /**
     * Auto-assignment when the conductor does not pick a seat: never the
     * wheelchair space (that must be chosen explicitly), priority seats first
     * for passengers entitled to them, otherwise the lowest-numbered free seat.
     */
    private Seat pickSeat(List<SeatState> states, PassengerCategory category) {
        boolean prefersPriority = category != PassengerCategory.ADULT;
        return states.stream()
                .filter(SeatState::selectable)
                .filter(s -> !s.seat().isWheelchairSpace())
                .min(Comparator.comparingInt((SeatState s) ->
                                prefersPriority == (s.seat().getSeatType() == SeatType.PRIORITY) ? 0 : 1)
                        .thenComparing(s -> s.seat().getSeatNumber()))
                .map(SeatState::seat)
                .orElseThrow(() -> TicketingException.conflict("No regular seat is free for this segment. "
                        + "A wheelchair space must be selected explicitly for a Divyangjan ticket."));
    }

    private Ticket findTicket(String ticketNumber) {
        return ticketRepository.findByTicketNumber(ticketNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket #" + ticketNumber + " does not exist."));
    }

    TicketResponse toResponse(TicketingJourney journey, Ticket t) {
        Integer until = t.occupiedUntilSequence();
        boolean onBoardNow = until != null && RouteSegments.onBoardAt(t.getFromSequence(), until, journey.currentSequence());
        FareQuote fareLabel = fareCalculator.quote(t.getPassengerCategory(), t.getSeat().getSeatType(), 0);
        return TicketResponse.builder()
                .ticketNumber(t.getTicketNumber())
                .busNumber(journey.busNumber())
                .routeCode(journey.routeCode())
                .seatNumber(t.getSeat().getSeatNumber())
                .seatType(t.getSeat().getSeatType())
                .passengerId(t.getPassenger().getId())
                .passengerName(t.getPassenger().getName())
                .passengerCategory(t.getPassengerCategory())
                .passengerCategoryLabel(t.getPassengerCategory().getLabel())
                .ticketType(t.getTicketType())
                .ticketTypeLabel(t.getTicketType().getLabel())
                .fromStopId(t.getFromStop().getId())
                .fromStop(t.getFromStop().getName())
                .fromSequence(t.getFromSequence())
                .toStopId(t.getToStop().getId())
                .toStop(t.getToStop().getName())
                .toSequence(t.getToSequence())
                .alightedAtStop(t.getAlightedSequence() != null ? journey.stopName(t.getAlightedSequence()) : null)
                .fare(t.getFare())
                .currency(properties.getFare().getCurrency())
                .fareLabel(fareLabel.getFareLabel())
                .status(t.getStatus())
                .onBoardNow(onBoardNow)
                .issuedAt(t.getIssuedAt())
                .completedAt(t.getCompletedAt())
                .issuedBy(t.getIssuedBy())
                .build();
    }

    static String label(Seat seat) {
        return seat.isWheelchairSpace() ? "Wheelchair space " + seat.getSeatNumber() : "Seat " + seat.getSeatNumber();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
