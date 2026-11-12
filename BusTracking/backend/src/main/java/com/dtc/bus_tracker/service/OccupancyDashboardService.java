package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.dto.BusOccupancySummary;
import com.dtc.bus_tracker.dto.CameraReport;
import com.dtc.bus_tracker.dto.LiveOccupancyResponse;
import com.dtc.bus_tracker.dto.SeatCounts;
import com.dtc.bus_tracker.dto.TicketingStopView;
import com.dtc.bus_tracker.entity.BusType;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.entity.TicketStatus;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.service.StandingZoneTracker.Snapshot;
import com.dtc.bus_tracker.service.StandingZoneTracker.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Real-time occupancy dashboard: every figure is computed from seats, tickets and camera readings. */
@Service
@RequiredArgsConstructor
public class OccupancyDashboardService {

    private final BusRepository busRepository;
    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final CameraMonitoringService cameraMonitoring;
    private final CameraOccupancyProvider cameraProvider;
    private final StandingAreaMonitor standingAreaMonitor;
    private final TicketingProperties properties;

    /** Seats (tickets) and standing area (camera) for the hop the bus is about to travel. */
    private record Occupancy(SeatCounts seats, int capacity, int seated, boolean cameraInstalled,
                             Snapshot standing, int standingPassengers, SeatAvailabilityIndicator.Result availability) {
    }

    @Transactional(readOnly = true)
    public List<BusOccupancySummary> summaries() {
        return busRepository.findByBusTypeIsNotNullOrderByVehicleIdAsc().stream()
                .map(bus -> summarize(journeyLoader.load(bus, false)))
                .toList();
    }

    @Transactional(readOnly = true)
    public BusOccupancySummary summary(String busNumber) {
        return summarize(journeyLoader.load(busNumber));
    }

    /** Live view for the conductor console; {@code conductorId} is the logged-in conductor. */
    @Transactional(readOnly = true)
    public LiveOccupancyResponse live(String busNumber, String conductorId) {
        TicketingJourney journey = journeyLoader.load(busNumber);
        Occupancy o = occupancy(journey);
        SeatCounts now = o.seats();
        int currentIndex = journey.indexOf(journey.currentSequence());
        List<Ticket> tickets = journey.tickets();
        List<Ticket> sold = tickets.stream().filter(t -> t.getStatus() != TicketStatus.CANCELLED).toList();
        Optional<Ticket> lastSold = sold.stream().max(Comparator.comparing(Ticket::getIssuedAt));
        List<Ticket> mine = sold.stream().filter(t -> conductorId != null && conductorId.equals(t.getIssuedBy())).toList();

        return LiveOccupancyResponse.builder()
                .busNumber(journey.busNumber())
                .busTypeLabel(journey.bus().getBusType().getLabel())
                .routeCode(journey.routeCode())
                .routeName(journey.trip().getRoute().getName())
                .origin(journey.stopName(journey.firstSequence()))
                .destination(journey.stopName(journey.lastSequence()))
                .tripStartedAt(journey.trip().getStartTime())
                .currentStop(journey.stopName(journey.currentSequence()))
                .nextStop(currentIndex + 1 < journey.stops().size()
                        ? journey.stops().get(currentIndex + 1).getStop().getName() : null)
                .conductorId(conductorId)
                .conductorTicketsIssued(mine.size())
                .conductorLastTicketAt(mine.stream().map(Ticket::getIssuedAt).max(Comparator.naturalOrder())
                        .map(OccupancyDashboardService::toInstant).orElse(null))
                .conductorInStandingArea(o.cameraInstalled() && o.standing().conductorInZone())
                .totalSeats(now.getTotalSeats())
                .occupiedSeats(now.getOccupiedSeats())
                .availableSeats(now.getAvailableRegular() + now.getAvailablePriority())
                .wheelchairSpaces(now.getWheelchairSpaces())
                .wheelchairStatus(wheelchairStatus(now))
                .capacity(o.capacity())
                .seatedPassengers(o.seated())
                .standingPassengers(o.standingPassengers())
                .passengersOnBoard(o.seated() + o.standingPassengers())
                .occupancyPercent(percent(o.seated(), o.capacity()))
                .seatAvailability(o.availability().status())
                .seatAvailabilityBasis(o.availability().basis())
                .seatAvailabilityReason(o.availability().reason())
                .cameraInstalled(o.cameraInstalled())
                .cameraStatus(cameraMonitoring.cameraStatus(journey.bus()))
                .presetFeed(cameraProvider.isSimulated())
                .activePresetId(o.cameraInstalled() ? cameraProvider.activePreset(journey.busNumber()) : null)
                .presets(o.cameraInstalled() ? cameraProvider.presets() : List.of())
                .standingArea(o.cameraInstalled() ? o.standing() : null)
                .ticketsSold(sold.size())
                .activeTickets(count(tickets, TicketStatus.ACTIVE))
                .completedTickets(count(tickets, TicketStatus.COMPLETED))
                .cancelledTickets(count(tickets, TicketStatus.CANCELLED))
                .fareCollected(sold.stream().map(Ticket::getFare).reduce(BigDecimal.ZERO, BigDecimal::add))
                .currency(properties.getFare().getCurrency())
                .lastTicketNumber(lastSold.map(Ticket::getTicketNumber).orElse(null))
                .lastTicketAt(lastSold.map(t -> toInstant(t.getIssuedAt())).orElse(null))
                .updatedAt(Instant.now())
                .build();
    }

    private Occupancy occupancy(TicketingJourney journey) {
        int current = journey.currentSequence();
        int currentIndex = journey.indexOf(current);
        int nextSeq = currentIndex + 1 < journey.stops().size()
                ? journey.stops().get(currentIndex + 1).getStopSequence()
                : current + 1;

        // "Now" is the hop the bus is about to travel (current stop -> next).
        SeatCounts now = seatAvailability.count(journey,
                seatAvailability.seatStates(journey, current, nextSeq, PassengerCategory.DIVYANGJAN));
        int capacity = now.getTotalSeats() + now.getWheelchairSpaces() - now.getUnavailableSeats();
        int seated = now.getOccupiedSeats() + now.getOccupiedWheelchair();

        boolean cameraInstalled = Boolean.TRUE.equals(journey.bus().getCameraInstalled());
        Snapshot standing = cameraInstalled ? standingAreaMonitor.snapshot(journey.busNumber()) : null;
        return new Occupancy(now, capacity, seated, cameraInstalled, standing,
                standing != null ? standing.passengerCount() : 0,
                SeatAvailabilityIndicator.derive(now.getAvailableRegular() + now.getAvailablePriority(),
                        cameraInstalled, standing));
    }

    private BusOccupancySummary summarize(TicketingJourney journey) {
        int currentIndex = journey.indexOf(journey.currentSequence());
        Occupancy o = occupancy(journey);
        SeatCounts now = o.seats();
        List<TicketingStopView> stops = seatAvailability.stopViews(journey);
        CameraReport camera = cameraMonitoring.report(journey);
        List<Ticket> tickets = journey.tickets();

        return BusOccupancySummary.builder()
                .busNumber(journey.busNumber())
                .busType(journey.bus().getBusType())
                .busTypeLabel(journey.bus().getBusType().getLabel())
                .lowFloor(journey.bus().getBusType() == BusType.LOW_FLOOR)
                .routeCode(journey.routeCode())
                .routeName(journey.trip().getRoute().getName())
                .stops(stops)
                .currentStop(stops.get(currentIndex))
                .nextStop(currentIndex + 1 < stops.size() ? stops.get(currentIndex + 1) : null)
                .totalSeats(now.getTotalSeats())
                .prioritySeats(now.getPrioritySeats())
                .wheelchairSpaces(now.getWheelchairSpaces())
                .availableSeats(now.getAvailableRegular())
                .reservedSeats(now.getAvailablePriority())
                .occupiedSeats(now.getOccupiedSeats())
                .unavailableSeats(now.getUnavailableSeats())
                .wheelchairStatus(wheelchairStatus(now))
                .onBoard(o.seated())
                .capacity(o.capacity())
                .occupancyPercent(percent(o.seated(), o.capacity()))
                .ticketsSold(tickets.stream().filter(t -> t.getStatus() != TicketStatus.CANCELLED).count())
                .activeTickets(count(tickets, TicketStatus.ACTIVE))
                .completedTickets(count(tickets, TicketStatus.COMPLETED))
                .cameraStatus(camera.getCameraStatus())
                .cameraSimulated(camera.isSimulated())
                .cameraLastScanAt(camera.getLastScanAt())
                .cameraMismatches(camera.getMismatches().size())
                .standingStatus((o.standing() != null ? o.standing().status() : Status.UNKNOWN).name())
                .standingPassengers(o.standingPassengers())
                .seatAvailability(o.availability().status().name())
                .seatAvailabilityReason(o.availability().reason())
                .segments(seatAvailability.hops(journey))
                .build();
    }

    private static String wheelchairStatus(SeatCounts now) {
        return now.getWheelchairSpaces() == 0 ? "NOT_FITTED"
                : now.getAvailableWheelchair() > 0 ? "AVAILABLE" : "OCCUPIED";
    }

    private static double percent(int onBoard, int capacity) {
        return capacity == 0 ? 0 : Math.round(onBoard * 1000.0 / capacity) / 10.0;
    }

    private static long count(List<Ticket> tickets, TicketStatus status) {
        return tickets.stream().filter(t -> t.getStatus() == status).count();
    }

    private static Instant toInstant(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
