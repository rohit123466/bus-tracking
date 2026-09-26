package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Trip;
import com.dtc.bus_tracker.entity.TripStatus;
import com.dtc.bus_tracker.exception.ResourceNotFoundException;
import com.dtc.bus_tracker.exception.TicketingException;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.repository.SeatRepository;
import com.dtc.bus_tracker.repository.StopTimeRepository;
import com.dtc.bus_tracker.repository.TicketRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolves a bus number to its in-progress trip and loads a
 * {@link TicketingJourney}. The journey's ordered stops come from the trip's
 * stop_times - the same source {@link RouteStopSequenceService} uses - so
 * ticketing reuses the existing route/trip/stop tables rather than keeping a
 * second copy of stop order.
 */
@Service
@RequiredArgsConstructor
public class TicketingJourneyLoader {

    private final BusRepository busRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;

    /** Read-only snapshot. */
    public TicketingJourney load(String busNumber) {
        return load(findTicketingBus(busNumber), false);
    }

    /**
     * Snapshot with the trip row locked for the rest of the caller's
     * transaction; use for every write (sale, completion, stop advance).
     */
    public TicketingJourney loadForUpdate(String busNumber) {
        return load(findTicketingBus(busNumber), true);
    }

    public TicketingJourney load(Bus bus, boolean lock) {
        Trip trip = tripRepository.findFirstByBus_IdAndStatusOrderByIdDesc(bus.getId(), TripStatus.IN_PROGRESS)
                .orElseThrow(() -> TicketingException.conflict(
                        "Bus " + bus.getVehicleId() + " has no journey in progress to sell tickets on."));
        if (lock) {
            trip = tripRepository.lockById(trip.getId()).orElseThrow();
        }

        List<StopTime> stops = stopTimeRepository.findByTrip_IdOrderByStopSequenceAsc(trip.getId());
        if (stops.size() < 2) {
            throw TicketingException.conflict("Journey of bus " + bus.getVehicleId() + " has fewer than two stops.");
        }
        return new TicketingJourney(bus, trip, stops,
                seatRepository.findByBus_IdOrderByIdAsc(bus.getId()),
                ticketRepository.findByTrip_IdOrderByIdAsc(trip.getId()));
    }

    public Bus findTicketingBus(String busNumber) {
        Bus bus = busRepository.findByVehicleId(busNumber == null ? "" : busNumber.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Bus " + busNumber + " does not exist."));
        if (bus.getBusType() == null) {
            throw TicketingException.badRequest("Bus " + busNumber + " has no seat layout configured, so it "
                    + "does not take part in seat-level ticketing.");
        }
        return bus;
    }
}
