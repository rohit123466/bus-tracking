package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.entity.Trip;

import java.util.List;
import java.util.Optional;

/**
 * Everything seat availability is computed from for one bus's current
 * journey, loaded once per request: the trip's ordered stops (with their
 * stop_sequence), the bus's seats and every ticket on the trip.
 */
public record TicketingJourney(Bus bus, Trip trip, List<StopTime> stops, List<Seat> seats, List<Ticket> tickets) {

    public int firstSequence() {
        return stops.getFirst().getStopSequence();
    }

    public int lastSequence() {
        return stops.getLast().getStopSequence();
    }

    /** Stop sequence the bus is at now; the first stop until the conductor advances it. */
    public int currentSequence() {
        Integer current = trip.getCurrentStopSequence();
        return current != null ? current : firstSequence();
    }

    public Optional<StopTime> stopAt(int sequence) {
        return stops.stream().filter(st -> st.getStopSequence() == sequence).findFirst();
    }

    /** The trip stop for a stops.id, if that stop is on this journey. */
    public Optional<StopTime> findStop(Long stopId) {
        return stops.stream().filter(st -> st.getStop().getId().equals(stopId)).findFirst();
    }

    public int indexOf(int sequence) {
        for (int i = 0; i < stops.size(); i++) {
            if (stops.get(i).getStopSequence() == sequence) return i;
        }
        return -1;
    }

    public String stopName(int sequence) {
        return stopAt(sequence).map(st -> st.getStop().getName()).orElse("stop #" + sequence);
    }

    public String busNumber() {
        return bus.getVehicleId();
    }

    public String routeCode() {
        return trip.getRoute().getRouteCode();
    }
}
