package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.BusDetailResponse;
import com.dtc.bus_tracker.dto.BusHistoryPoint;
import com.dtc.bus_tracker.dto.NearbyBusResponse;
import com.dtc.bus_tracker.dto.PassingBusResponse;
import com.dtc.bus_tracker.service.BusHistoryService;
import com.dtc.bus_tracker.service.BusSearchService;
import com.dtc.bus_tracker.service.NearbyBusService;
import com.dtc.bus_tracker.service.PassingBusService;
import com.dtc.bus_tracker.util.RequestBounds;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buses")
@RequiredArgsConstructor
public class BusController {

    private final NearbyBusService nearbyBusService;
    private final BusSearchService busSearchService;
    private final PassingBusService passingBusService;
    private final BusHistoryService busHistoryService;

    @GetMapping("/nearby")
    public ResponseEntity<List<NearbyBusResponse>> getNearbyBuses(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "5000") double radiusMeters,
            @RequestParam(defaultValue = "10") int limit) {

        List<NearbyBusResponse> response = nearbyBusService.findNearbyBuses(
                lat, lng, RequestBounds.radius(radiusMeters), RequestBounds.limit(limit));
        return ResponseEntity.ok(response);
    }

    // GET /api/buses/{vehicleId} - "Search by Bus Number": live location,
    // current/next stop, ETA, and the complete route.
    @GetMapping("/{vehicleId}")
    public ResponseEntity<BusDetailResponse> getBusByVehicleId(@PathVariable String vehicleId) {
        return ResponseEntity.ok(busSearchService.findByVehicleId(vehicleId));
    }

    // GET /api/buses/passing-near - "which bus will pass near me": every live
    // bus on a route serving a stop within radius, regardless of the bus's
    // current distance, with destination and ETA to that stop.
    @GetMapping("/passing-near")
    public ResponseEntity<List<PassingBusResponse>> getBusesPassingNear(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "1000") double radiusMeters,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(passingBusService.findBusesPassingNear(
                lat, lng, RequestBounds.radius(radiusMeters), RequestBounds.limit(limit)));
    }

    // GET /api/buses/{vehicleId}/history - "Journey Replay": recorded
    // positions in chronological order, ready to animate on a map.
    @GetMapping("/{vehicleId}/history")
    public ResponseEntity<List<BusHistoryPoint>> getBusHistory(@PathVariable String vehicleId) {
        return ResponseEntity.ok(busHistoryService.getHistory(vehicleId));
    }
}