package com.dtc.bus_tracker.live;

import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
  private final ServiceAlertService alerts;
  // Field-injected so the existing five-argument constructor (used by tests) is unchanged.
  @Autowired private RegionService regions;
  @Autowired private RegionalBusesCache regionalCache;
  @Autowired private RouteLearner learner;
  @Autowired private JourneyService journeys;
  @Autowired private StandService stands;
  @Autowired private StandRouteService standRoutes;

  public TransitController(
      TransitIndex index,
      LiveTransitService transit,
      WalkingService walking,
      AlternativeService alternatives,
      ServiceAlertService alerts) {
    this.index = index;
    this.transit = transit;
    this.walking = walking;
    this.alternatives = alternatives;
    this.alerts = alerts;
  }

  /** How many routes running right now have a path learned from live GPS. */
  @GetMapping("/learning/status")
  public RouteLearner.Status learningStatus() {
    return learner.status();
  }

  /** Public route numbers that have at least one stop in the requested region. */
  @GetMapping("/regions/routes")
  public RegionService.EligibleRoutes regionRoutes(
      @RequestParam(defaultValue = "ANY") String region) {
    return regions.routes(RegionService.Filter.parse(region));
  }

  /**
   * Live buses on region-eligible routes (optionally one route number) that are currently inside
   * the area; {@code insideAreaOnly=false} also returns those elsewhere in Delhi. The body is a
   * {@link RegionService.RegionalBuses}, served pre-encoded from {@link RegionalBusesCache}: gzip
   * when the client accepts it, 304 when its ETag still matches. {@value #NEXT_UPDATE_HEADER} says
   * how many milliseconds until the server expects its next feed snapshot.
   */
  @GetMapping("/buses/regional")
  public ResponseEntity<byte[]> regionalBuses(
      @RequestParam(defaultValue = "ANY") String region,
      @RequestParam(defaultValue = "") String routeNumber,
      @RequestParam(defaultValue = "true") boolean insideAreaOnly,
      @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    var encoded = regionalCache.get(RegionService.Filter.parse(region), routeNumber, insideAreaOnly);
    var response =
        ResponseEntity.status(
                encoded.etag().equals(ifNoneMatch) ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
            .eTag(encoded.etag())
            // Stored but always revalidated: never shown stale, unchanged answers cost a 304.
            .cacheControl(CacheControl.noCache())
            .varyBy(HttpHeaders.ACCEPT_ENCODING)
            .header(NEXT_UPDATE_HEADER, String.valueOf(regionalCache.nextUpdateInMs()));
    if (encoded.etag().equals(ifNoneMatch)) return response.build();
    boolean gzip = acceptEncoding != null && acceptEncoding.toLowerCase(Locale.ROOT).contains("gzip");
    if (gzip) response.header(HttpHeaders.CONTENT_ENCODING, "gzip");
    return response.contentType(MediaType.APPLICATION_JSON).body(gzip ? encoded.gzip() : encoded.json());
  }

  public static final String NEXT_UPDATE_HEADER = "X-Next-Update-In";

  /** Nearby stops inside the area served by region-eligible routes, with upcoming buses. */
  @GetMapping("/stops/nearby-regional")
  public RegionService.RegionalStops regionalStops(
      @RequestParam double latitude,
      @RequestParam double longitude,
      @RequestParam(defaultValue = "1000") double radiusMeters,
      @RequestParam(defaultValue = "5") int limit,
      @RequestParam(defaultValue = "ANY") String region,
      @RequestParam(defaultValue = "") String routeNumber) {
    coordinates(latitude, longitude);
    query(routeNumber);
    return regions.nearbyStops(
        latitude, longitude, radiusMeters, limit, RegionService.Filter.parse(region), routeNumber);
  }

  @GetMapping("/buses/live")
  public LiveTransitService.LiveResult buses(@RequestParam(defaultValue = "") String routeNumber) {
    query(routeNumber);
    return transit.buses(routeNumber);
  }

  /**
   * Returns all GTFS-verified live buses within {@code radiusMeters} of the user's GPS position,
   * sorted nearest-first. Optional {@code routeNumber} narrows results to a single route.
   * This is the GTFS-realtime equivalent of the legacy {@code /api/buses/nearby} endpoint;
   * the two must remain separate business functions (nearby vs. route-search).
   */
  @GetMapping("/buses/nearby")
  public LiveTransitService.NearbyResult nearbyBuses(
      @RequestParam double latitude,
      @RequestParam double longitude,
      @RequestParam(defaultValue = "2000") double radiusMeters,
      @RequestParam(defaultValue = "") String routeNumber) {
    coordinates(latitude, longitude);
    query(routeNumber);
    return transit.nearbyBuses(latitude, longitude, radiusMeters, routeNumber);
  }

  @GetMapping("/routes/search")
  public List<String> routes(@RequestParam(defaultValue = "") String query) {
    query(query);
    String q = query.trim().toUpperCase(Locale.ROOT);
    if (q.isEmpty()) return List.of();
    return index.routes.values().stream()
        .map(TransitIndex.Route::number)
        .filter(Objects::nonNull)
        // Exact match first, then prefix-startsWith — exact results sort to top.
        // This ensures "534" returns "534" before "534A", "534B" etc., and
        // "1060" does not appear in results for query "106".
        .filter(n -> n.startsWith(q))
        .distinct()
        .sorted(Comparator.comparingInt((String n) -> n.equals(q) ? 0 : 1).thenComparing(Comparator.naturalOrder()))
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

  /** Ordered GTFS stop pattern per route_id (direction) of a public route number. */
  @GetMapping("/routes/patterns")
  public List<JourneyService.Pattern> patterns(@RequestParam String routeNumber) {
    query(routeNumber);
    return journeys.patterns(routeNumber);
  }

  /** Direct and one-transfer journeys between two points over the GTFS stop patterns. */
  @GetMapping("/journey/plan")
  public JourneyService.Plan journey(
      @RequestParam double fromLat,
      @RequestParam double fromLng,
      @RequestParam double toLat,
      @RequestParam double toLng,
      @RequestParam(defaultValue = "FASTEST") String preference) {
    coordinates(fromLat, fromLng);
    coordinates(toLat, toLng);
    query(preference);
    return journeys.plan(fromLat, fromLng, toLat, toLng, preference);
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

  /**
   * Bus stands whose name matches {@code query} (partial, case-insensitive, any word order), each
   * stand once: same-name stops close together are merged. Unlike {@code /stops/search}, which
   * lists every GTFS stop (one per side of the road), this is meant for autocomplete.
   */
  @GetMapping("/stands/search")
  public List<StandService.Stand> standSearch(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(defaultValue = "10") int limit) {
    query(query);
    return stands.search(query, limit);
  }

  /**
   * Buses heading to the stand that contains {@code stopId}: live GPS positions with ETA,
   * distance, destination and status, plus timetable departures (labelled SCHEDULED) for routes
   * with no live bus. {@value #NEXT_UPDATE_HEADER} says when the next feed snapshot is expected.
   */
  @GetMapping("/stands/{stopId}/arrivals")
  public ResponseEntity<StandService.StandArrivals> standArrivals(@PathVariable String stopId) {
    query(stopId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(NEXT_UPDATE_HEADER, String.valueOf(regionalCache.nextUpdateInMs()))
        .body(stands.arrivals(stopId));
  }

  /**
   * Every route whose timetable stop sequence calls at the pick-up stand ({@code from}, any stop ID
   * of it) and later at the destination stand ({@code to}), with the stops between, scheduled ride
   * time, distance and how many of its buses are on the live feed. {@code walkMeters} (0–500) also
   * admits stops that close to either stand.
   */
  @GetMapping("/stands/routes")
  public StandRouteService.RouteSearch standRoutes(
      @RequestParam(defaultValue = "") String from,
      @RequestParam(defaultValue = "") String to,
      @RequestParam(defaultValue = "0") double walkMeters) {
    if (from.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a pick-up bus stand");
    if (to.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a destination bus stand");
    query(from);
    query(to);
    return standRoutes.search(from.trim(), to.trim(), walkMeters);
  }

  /**
   * Buses on the live feed mapped to the given route IDs (comma-separated, from a
   * {@code /stands/routes} match), with their position, next stop and, when it can be estimated,
   * their arrival at {@code pickup}.
   */
  @GetMapping("/stands/routes/live")
  public ResponseEntity<StandRouteService.RouteLive> standRouteLive(
      @RequestParam String pickup, @RequestParam String routeIds) {
    query(pickup);
    query(routeIds);
    var ids = Arrays.stream(routeIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).limit(10).toList();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(NEXT_UPDATE_HEADER, String.valueOf(regionalCache.nextUpdateInMs()))
        .body(standRoutes.live(pickup.trim(), ids));
  }

  @GetMapping("/routes/alternatives")
  public AlternativeService.Result alternatives(
      @RequestParam String sourceStopId,
      @RequestParam(required = false) String destinationStopId,
      @RequestParam(required = false) String routeNumber) {
    return alternatives.alternatives(sourceStopId, destinationStopId, routeNumber);
  }

  /**
   * Nearest bus stands to the user's GPS location, enriched with:
   * <ul>
   *   <li>{@code distanceMeters} — Haversine distance from the user</li>
   *   <li>{@code walkMinutes} — straight-line walking estimate (5 km/h)</li>
   *   <li>{@code routeNumbers} — public route numbers that serve this stop</li>
   *   <li>{@code liveCount} — number of fresh vehicles currently approaching</li>
   * </ul>
   * Results are sorted nearest-first. {@code limit} is capped at 20.
   * This uses the GTFS static index so it is always available regardless of
   * live feed state.
   */
  public record NearbyStop(
      String id,
      String name,
      double lat,
      double lng,
      double distanceMeters,
      double walkMinutes,
      List<String> routeNumbers,
      int liveCount) {}

  public record NearbyStopsResult(
      List<NearbyStop> stops,
      double radiusMeters,
      boolean feedAvailable) {}

  @GetMapping("/stops/nearby")
  public NearbyStopsResult nearbyStops(
      @RequestParam double latitude,
      @RequestParam double longitude,
      @RequestParam(defaultValue = "1000") double radiusMeters,
      @RequestParam(defaultValue = "5") int limit) {
    coordinates(latitude, longitude);
    double radius = Math.max(100, Math.min(5000, radiusMeters));
    int cap = Math.max(1, Math.min(limit, 20));

    // 1. Get stops within radius from GTFS spatial index, already sorted nearest-first
    List<TransitIndex.Stop> raw = index.nearby(latitude, longitude, radius);

    // 2. For live counts: snapshot of fresh vehicles grouped by which stops they serve
    //    We compute this once here rather than calling arrivals() per stop (which would be O(n*m))
    Map<String, Integer> liveAtStop = new HashMap<>();
    for (var v : transit.liveVehicles()) {
      if (!RealtimeSnapshot.fresh(v.timestamp())) continue;
      var route = transit.route(v);
      if (route == null) continue;
      for (String stopId : index.routesAtStop.keySet()) {
        if (index.routesAtStop.get(stopId).contains(route.id())) {
          // Only count if the bus is plausibly approaching: it hasn't passed the stop yet
          // (stop sequence >= bus current sequence, or sequence unknown)
          var trip = index.trips.get(v.tripId());
          if (trip != null && v.sequence() != null) {
            boolean stopIsAhead = trip.visits().stream()
                .anyMatch(vis -> vis.stopId().equals(stopId) && vis.sequence() >= v.sequence());
            if (!stopIsAhead) continue;
          }
          liveAtStop.merge(stopId, 1, Integer::sum);
        }
      }
    }

    boolean feedAvailable = transit.feedAvailable();

    // 3. Build enriched NearbyStop records
    List<NearbyStop> result = raw.stream()
        .limit(cap)
        .map(s -> {
          double dist = Geometry.distance(latitude, longitude, s.lat(), s.lng());
          double walkMin = dist / (5000.0 / 60.0); // 5 km/h walking speed

          // Collect distinct public route numbers for this stop
          List<String> routeNums = index.routesAtStop
              .getOrDefault(s.id(), Set.of())
              .stream()
              .map(routeId -> index.routes.get(routeId))
              .filter(Objects::nonNull)
              .map(TransitIndex.Route::number)
              .filter(Objects::nonNull)
              .distinct()
              .sorted()
              .toList();

          int live = liveAtStop.getOrDefault(s.id(), 0);
          return new NearbyStop(
              s.id(), s.name(), s.lat(), s.lng(),
              Math.round(dist * 10.0) / 10.0,
              Math.round(walkMin * 10.0) / 10.0,
              routeNums, live);
        })
        .toList();

    return new NearbyStopsResult(result, radius, feedAvailable);
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

  /** Service alerts for the current snapshot — filtered to a route when provided. */
  @GetMapping("/alerts")
  public ServiceAlertService.AlertsResult serviceAlerts(
      @RequestParam(defaultValue = "") String routeNumber) {
    query(routeNumber);
    return alerts.alerts(routeNumber);
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
