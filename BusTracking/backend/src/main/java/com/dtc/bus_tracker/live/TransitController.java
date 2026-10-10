package com.dtc.bus_tracker.live;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** GTFS string IDs deliberately namespaced separately from legacy numeric database IDs. */
@RestController
@RequestMapping("/api/transit")
public class TransitController {
  private final TransitIndex index;
  private final LiveTransitService transit;
  private final WalkingService walking;
  private final AlternativeService alternatives;

  public TransitController(
      TransitIndex index,
      LiveTransitService transit,
      WalkingService walking,
      AlternativeService alternatives) {
    this.index = index;
    this.transit = transit;
    this.walking = walking;
    this.alternatives = alternatives;
  }

  @GetMapping("/buses/live")
  public LiveTransitService.LiveResult buses(@RequestParam(defaultValue = "") String routeNumber) {
    query(routeNumber);
    return transit.buses(routeNumber);
  }

  @GetMapping("/routes/search")
  public List<String> routes(@RequestParam(defaultValue = "") String query) {
    query(query);
    String q = query.trim().toUpperCase(Locale.ROOT);
    return index.routes.values().stream()
        .map(TransitIndex.Route::number)
        .filter(Objects::nonNull)
        .filter(n -> n.startsWith(q))
        .distinct()
        .sorted()
        .limit(10)
        .toList();
  }

  public record RouteMap(List<TransitIndex.Stop> stops, List<List<TransitIndex.Point>> shapes) {}

  @GetMapping("/routes/map")
  public RouteMap map(@RequestParam String routeNumber) {
    query(routeNumber);
    Set<String> routes = new HashSet<>();
    index.routes.values().stream()
        .filter(r -> routeNumber.equalsIgnoreCase(r.number()))
        .forEach(r -> routes.add(r.id()));
    var stops =
        index.stops.values().stream()
            .filter(
                s ->
                    index.routesAtStop.getOrDefault(s.id(), Set.of()).stream()
                        .anyMatch(routes::contains))
            .toList();
    var shapes =
        index.trips.values().stream()
            .filter(t -> routes.contains(t.routeId()))
            .map(TransitIndex.Trip::shapeId)
            .distinct()
            .map(index.shapes::get)
            .filter(Objects::nonNull)
            .toList();
    return new RouteMap(stops, shapes);
  }

  @GetMapping("/stops/search")
  public List<TransitIndex.Stop> stops(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "") String routeNumber) {
    query(query);
    query(routeNumber);
    String q = query.trim().toLowerCase(Locale.ROOT);
    Set<String> routes = new HashSet<>();
    index.routes.values().stream()
        .filter(r -> routeNumber.equalsIgnoreCase(r.number()))
        .forEach(r -> routes.add(r.id()));
    return index.stops.values().stream()
        .filter(
            s ->
                routeNumber.isBlank()
                    || index.routesAtStop.getOrDefault(s.id(), Set.of()).stream()
                        .anyMatch(routes::contains))
        .filter(
            s -> q.isBlank() || s.name().toLowerCase(Locale.ROOT).contains(q) || s.id().equals(q))
        .sorted(Comparator.comparing(TransitIndex.Stop::name))
        .limit(200)
        .toList();
  }

  @GetMapping("/stops/{stopId}/arrivals")
  public LiveTransitService.Arrivals arrivals(@PathVariable String stopId) {
    return transit.arrivals(stopId);
  }

  @GetMapping("/routes/alternatives")
  public AlternativeService.Result alternatives(
      @RequestParam String sourceStopId,
      @RequestParam(required = false) String destinationStopId,
      @RequestParam(required = false) String routeNumber) {
    return alternatives.alternatives(sourceStopId, destinationStopId, routeNumber);
  }

  @GetMapping("/stops/nearest")
  public WalkingService.Walk nearest(
      @RequestParam double latitude, @RequestParam double longitude) {
    coordinates(latitude, longitude);
    return walking.nearest(latitude, longitude);
  }

  @GetMapping("/navigation/walking")
  public WalkingService.Walk walking(
      @RequestParam double startLat, @RequestParam double startLon, @RequestParam String stopId) {
    coordinates(startLat, startLon);
    return walking.route(startLat, startLon, transit.stop(stopId));
  }

  private static void query(String q) {
    if (q.length() > 100)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Query must be 100 characters or fewer");
  }

  private static void coordinates(double lat, double lng) {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lng)
        || lat < 27
        || lat > 30
        || lng < 75
        || lng > 79)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordinates must be in Delhi/NCR");
  }
}
