package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.BusOccupancySummary;
import com.dtc.bus_tracker.dto.SeatMapResponse;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.service.OccupancyDashboardService;
import com.dtc.bus_tracker.service.SeatAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Rider-facing seat availability - public like the rest of the rider API.
 * Returns seat states and counts only, never passenger names.
 */
@RestController
@RequestMapping("/api/ticketing")
@RequiredArgsConstructor
public class TicketingController {

    private final OccupancyDashboardService occupancyDashboardService;
    private final SeatAvailabilityService seatAvailabilityService;

    // GET /api/ticketing/buses - buses with seat-level ticketing and their
    // live occupancy (as of each bus's current stop) and per-hop availability.
    @GetMapping("/buses")
    public ResponseEntity<List<BusOccupancySummary>> getBuses() {
        return ResponseEntity.ok(occupancyDashboardService.summaries());
    }

    @GetMapping("/buses/{busNumber}")
    public ResponseEntity<BusOccupancySummary> getBus(@PathVariable String busNumber) {
        return ResponseEntity.ok(occupancyDashboardService.summary(busNumber));
    }

    // GET /api/ticketing/buses/DTC-101/seats?fromStopId=..&toStopId=..&category=ADULT
    // Seat map for a journey segment. Stops default to "current stop -> next stop".
    @GetMapping("/buses/{busNumber}/seats")
    public ResponseEntity<SeatMapResponse> getSeatMap(
            @PathVariable String busNumber,
            @RequestParam(required = false) Long fromStopId,
            @RequestParam(required = false) Long toStopId,
            @RequestParam(required = false) PassengerCategory category) {
        return ResponseEntity.ok(seatAvailabilityService.seatMap(busNumber, fromStopId, toStopId, category));
    }
}
