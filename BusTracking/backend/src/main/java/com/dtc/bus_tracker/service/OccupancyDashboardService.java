package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusOccupancySummary;
import com.dtc.bus_tracker.dto.CameraReport;
import com.dtc.bus_tracker.dto.SeatCounts;
import com.dtc.bus_tracker.dto.TicketingStopView;
import com.dtc.bus_tracker.entity.BusType;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.entity.TicketStatus;
import com.dtc.bus_tracker.repository.BusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Real-time occupancy dashboard: every figure is computed from seats, tickets and camera scans. */
@Service
@RequiredArgsConstructor
public class OccupancyDashboardService {

    private final BusRepository busRepository;
    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final CameraMonitoringService cameraMonitoring;

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

    private BusOccupancySummary summarize(TicketingJourney journey) {
        int current = journey.currentSequence();
        int currentIndex = journey.indexOf(current);
        int nextSeq = currentIndex + 1 < journey.stops().size()
                ? journey.stops().get(currentIndex + 1).getStopSequence()
                : current + 1;

        // "Now" is the hop the bus is about to travel (current stop -> next).
        SeatCounts now = seatAvailability.count(journey,
                seatAvailability.seatStates(journey, current, nextSeq, PassengerCategory.DIVYANGJAN));
        List<TicketingStopView> stops = seatAvailability.stopViews(journey);
        CameraReport camera = cameraMonitoring.report(journey);

        int capacity = now.getTotalSeats() + now.getWheelchairSpaces() - now.getUnavailableSeats();
        int onBoard = now.getOccupiedSeats() + now.getOccupiedWheelchair();
        List<Ticket> tickets = journey.tickets();

        String wheelchairStatus = now.getWheelchairSpaces() == 0 ? "NOT_FITTED"
                : now.getAvailableWheelchair() > 0 ? "AVAILABLE" : "OCCUPIED";

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
                .wheelchairStatus(wheelchairStatus)
                .onBoard(onBoard)
                .capacity(capacity)
                .occupancyPercent(capacity == 0 ? 0 : Math.round(onBoard * 1000.0 / capacity) / 10.0)
                .ticketsSold(tickets.stream().filter(t -> t.getStatus() != TicketStatus.CANCELLED).count())
                .activeTickets(tickets.stream().filter(t -> t.getStatus() == TicketStatus.ACTIVE).count())
                .completedTickets(tickets.stream().filter(t -> t.getStatus() == TicketStatus.COMPLETED).count())
                .cameraStatus(camera.getCameraStatus())
                .cameraSimulated(camera.isSimulated())
                .cameraLastScanAt(camera.getLastScanAt())
                .cameraMismatches(camera.getMismatches().size())
                .segments(seatAvailability.hops(journey))
                .build();
    }
}
