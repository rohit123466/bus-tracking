package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.StopDto;
import com.dtc.bus_tracker.dto.StopRouteInfo;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.exception.ResourceNotFoundException;
import com.dtc.bus_tracker.mapper.StopMapper;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.service.StopDetailService;
import com.dtc.bus_tracker.util.GeoUtils;
import com.dtc.bus_tracker.util.RequestBounds;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stops")
@RequiredArgsConstructor
public class StopController {

    private final StopRepository stopRepository;
    private final StopDetailService stopDetailService;
    private final StopMapper stopMapper;

    @GetMapping
    public ResponseEntity<Page<StopDto>> getAllStops(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(RequestBounds.page(page), RequestBounds.pageSize(size));
        Page<Stop> stops = stopRepository.findAll(pageable);
        return ResponseEntity.ok(stops.map(this::toDto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<StopDto> getStopById(@PathVariable Long id) {
        Stop stop = stopRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stop not found: " + id));
        return ResponseEntity.ok(toDto(stop));
    }

    // GET /api/stops/{id}/routes - which routes serve this stop, and the ETA
    // of the nearest live bus on each (fills the gap noted in BACKEND_NOTES.md).
    @GetMapping("/{id}/routes")
    public ResponseEntity<List<StopRouteInfo>> getRoutesServingStop(@PathVariable Long id) {
        return ResponseEntity.ok(stopDetailService.routesServing(id));
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<StopDto>> getNearbyStops(@RequestParam double lat,
                                                        @RequestParam double lng,
                                                        @RequestParam(defaultValue = "5000") double radiusMeters,
                                                        @RequestParam(defaultValue = "10") int limit) {
        double radius = RequestBounds.radius(radiusMeters);
        List<Stop> allStops = stopRepository.findAll();
        List<Stop> nearby = allStops.stream()
                .filter(s -> GeoUtils.haversine(lat, lng, s.getLatitude(), s.getLongitude()) <= radius)
                .sorted((s1, s2) -> Double.compare(
                        GeoUtils.haversine(lat, lng, s1.getLatitude(), s1.getLongitude()),
                        GeoUtils.haversine(lat, lng, s2.getLatitude(), s2.getLongitude())))
                .limit(RequestBounds.limit(limit))
                .collect(Collectors.toList());
        return ResponseEntity.ok(nearby.stream().map(this::toDto).collect(Collectors.toList()));
    }

    private StopDto toDto(Stop stop) {
        return stopMapper.toDto(stop);
    }
}