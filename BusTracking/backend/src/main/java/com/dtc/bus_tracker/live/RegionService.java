package com.dtc.bus_tracker.live;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Route-based North / North-West Delhi filtering for the Live Map.
 *
 * <p>A public route number is <em>eligible</em> when any GTFS route carrying that number has at
 * least one stop inside the configured region box ({@link TransitIndex#computeRegions()}).
 * Eligibility never depends on where a bus currently is. Vehicle positions come only from the
 * realtime snapshot; nothing here simulates or invents a position or an arrival time.
 */
@Service
public class RegionService {
  public enum Filter {
    NORTH,
    NORTH_WEST,
    ANY;

    public static Filter parse(String raw) {
      String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
      if (v.isEmpty() || v.equals("ANY") || v.equals("BOTH")) return ANY;
      try {
        return valueOf(v);
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "region must be NORTH, NORTH_WEST or ANY");
      }
    }
  }

  /** Below this speed an instantaneous-speed arrival estimate is meaningless. */
  static final double MIN_ESTIMATE_SPEED_KMH = 5;
  /** Longer chains than this are not offered as an estimate. */
  static final double MAX_ESTIMATE_MINUTES = 90;
  /** A "next stop" farther than this from the bus contradicts the reported sequence. */
  static final double MAX_NEXT_STOP_METERS = 5000;
  /**
   * Without a reported stop sequence, a bus is located on the straight lines joining its trip's
   * stops. Further than this from that path it is not considered to be running the trip (depot,
   * diversion or a stale trip assignment) and no progress is inferred.
   */
  static final double MAX_PATH_OFFSET_METERS = 300;
  /** A stop this far behind the bus's projected position counts as already passed. */
  static final double PASSED_TOLERANCE_METERS = 30;

  public static final String SOURCE_FEED = "FEED_STOP_SEQUENCE";
  public static final String SOURCE_INFERRED = "INFERRED_FROM_GPS_ON_ROUTE";

  public record RegionalBus(
      String vehicleId,
      String routeId,
      String routeCode,
      String direction,
      String tripId,
      double busLatitude,
      double busLongitude,
      Double speedKmh,
      long lastUpdatedEpochSeconds,
      String mappingSource,
      Double distanceFromShapeMeters,
      boolean offRouteFlag,
      List<String> regions,
      String nextStopId,
      String nextStopName,
      /** {@link #SOURCE_FEED} or {@link #SOURCE_INFERRED}; null when the next stop is unknown. */
      String nextStopSource,
      Double nextStopDistanceMeters,
      Double nextStopEtaMinutes,
      String nextStopEtaBasis) {}

  public record RegionalBuses(
      List<RegionalBus> buses,
      List<String> warnings,
      long fetchedAt,
      boolean feedAvailable,
      String region,
      int eligibleRouteCount,
      int excludedByRegion) {}

  public record EligibleRoutes(
      String region,
      List<String> routeNumbers,
      Map<String, TransitIndex.Bounds> definition,
      String method) {}

  public record Upcoming(
      String routeNumber,
      String vehicleId,
      Double etaMinutes,
      String availability,
      String label,
      String reason,
      Integer remainingStops,
      long lastGpsUpdate) {}

  public record RegionalStop(
      String id,
      String name,
      double lat,
      double lng,
      double distanceMeters,
      double walkMinutes,
      String walkBasis,
      List<String> routeNumbers,
      int liveCount,
      List<Upcoming> upcoming) {}

  public record RegionalStops(
      List<RegionalStop> stops,
      double radiusMeters,
      boolean feedAvailable,
      String region,
      List<String> warnings) {}

  private final TransitIndex index;
  private final LiveTransitService transit;
  private final RealtimeSnapshot live;

  public RegionService(TransitIndex index, LiveTransitService transit, RealtimeSnapshot live) {
    this.index = index;
    this.transit = transit;
    this.live = live;
  }

  /** Public route number -> union of regions across every GTFS route that carries it. */
  Map<String, EnumSet<TransitIndex.Region>> regionsByNumber() {
    Map<String, EnumSet<TransitIndex.Region>> out = new HashMap<>();
    for (var e : index.routeRegions.entrySet()) {
      var route = index.routes.get(e.getKey());
      if (route == null || route.number() == null) continue;
      out.computeIfAbsent(route.number(), k -> EnumSet.noneOf(TransitIndex.Region.class))
          .addAll(e.getValue());
    }
    return out;
  }

  static boolean matches(EnumSet<TransitIndex.Region> regions, Filter filter) {
    if (regions == null || regions.isEmpty()) return false;
    return switch (filter) {
      case NORTH -> regions.contains(TransitIndex.Region.NORTH);
      case NORTH_WEST -> regions.contains(TransitIndex.Region.NORTH_WEST);
      case ANY -> true;
    };
  }

  public Set<String> eligibleNumbers(Filter filter) {
    return regionsByNumber().entrySet().stream()
        .filter(e -> matches(e.getValue(), filter))
        .map(Map.Entry::getKey)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  public EligibleRoutes routes(Filter filter) {
    Map<String, TransitIndex.Bounds> def = new LinkedHashMap<>();
    for (var r : TransitIndex.Region.values()) def.put(r.name(), index.bounds(r));
    return new EligibleRoutes(
        filter.name(),
        List.copyOf(eligibleNumbers(filter)),
        def,
        "A route is eligible when at least one of its GTFS stops lies inside the region box.");
  }

  public RegionalBuses buses(Filter filter) {
    var all = transit.buses("");
    var byNumber = regionsByNumber();
    Set<String> eligible = eligibleNumbers(filter);
    Map<String, RealtimeSnapshot.Vehicle> vehicles = new HashMap<>();
    for (var v : transit.liveVehicles()) vehicles.put(v.id(), v);

    List<RegionalBus> out = new ArrayList<>();
    int excluded = 0;
    for (var b : all.buses()) {
      if (!eligible.contains(b.routeCode())) {
        excluded++;
        continue;
      }
      var regions =
          byNumber.getOrDefault(b.routeCode(), EnumSet.noneOf(TransitIndex.Region.class)).stream()
              .map(Enum::name)
              .toList();
      var next = nextStop(vehicles.get(b.vehicleId()));
      out.add(
          new RegionalBus(
              b.vehicleId(), b.routeId(), b.routeCode(), b.direction(), b.tripId(),
              b.busLatitude(), b.busLongitude(), b.speedKmh(), b.lastUpdatedEpochSeconds(),
              b.mappingSource(), b.distanceFromShapeMeters(), b.offRouteFlag(), regions,
              next == null ? null : next.stop().id(),
              next == null ? null : next.stop().name(),
              next == null ? null : next.source(),
              next == null ? null : next.distance(),
              next == null ? null : next.etaMinutes(),
              next == null || next.etaMinutes() == null ? null : "STOP_CHAIN_ESTIMATE"));
    }
    List<String> warnings = new ArrayList<>(all.warnings());
    long unknownTrips = out.stream().filter(b -> !index.trips.containsKey(b.tripId())).count();
    if (unknownTrips > 0)
      warnings.add(
          unknownTrips + " shown buses report trip IDs missing from the static GTFS; their next"
              + " stop is inferred from the route's stop pattern where unambiguous.");
    if (index.routeRegions.isEmpty())
      warnings.add("No GTFS route has a stop inside the configured North/North-West boxes.");
    return new RegionalBuses(
        out, warnings, all.fetchedAt(), all.feedAvailable(), filter.name(), eligible.size(),
        excluded);
  }

  record Next(TransitIndex.Stop stop, double distance, Double etaMinutes, String source) {}

  /**
   * A vehicle's position along its trip, measured on the straight lines joining consecutive stops.
   * {@code cumulative[i]} is the path distance of visit {@code i} from the first stop.
   */
  record Progress(double along, double[] cumulative, List<TransitIndex.Visit> visits) {
    int indexOf(String stopId) {
      for (int i = 0; i < visits.size(); i++) if (visits.get(i).stopId().equals(stopId)) return i;
      return -1;
    }
  }

  /**
   * Locates a vehicle on its trip's stop-to-stop path. Returns null for loops (a repeated stop
   * makes the position ambiguous), when the bus is too far from the path, or when it is moving
   * against the trip's direction of travel.
   */
  Progress locate(RealtimeSnapshot.Vehicle v, TransitIndex.Trip trip) {
    var visits = trip.visits();
    if (visits.size() < 2) return null;
    if (visits.stream().map(TransitIndex.Visit::stopId).distinct().count() != visits.size())
      return null;
    List<TransitIndex.Point> points = new ArrayList<>(visits.size());
    double[] cumulative = new double[visits.size()];
    TransitIndex.Stop prev = null;
    for (int i = 0; i < visits.size(); i++) {
      var s = index.stops.get(visits.get(i).stopId());
      if (s == null) return null;
      points.add(new TransitIndex.Point(s.lat(), s.lng(), i));
      cumulative[i] =
          prev == null ? 0 : cumulative[i - 1] + Geometry.distance(prev.lat(), prev.lng(), s.lat(), s.lng());
      prev = s;
    }
    var p = Geometry.project(points, v.lat(), v.lng());
    if (p.offset() > MAX_PATH_OFFSET_METERS) return null;
    if (v.bearing() != null && v.speedKmh() != null && v.speedKmh() > 3) {
      double delta = Math.abs(v.bearing() - p.bearing());
      if (Math.min(delta, 360 - delta) > 100) return null;
    }
    return new Progress(p.along(), cumulative, visits);
  }

  // GTFS route_id -> one representative trip per distinct stop pattern. Built lazily, once.
  private volatile Map<String, List<TransitIndex.Trip>> patterns;

  List<TransitIndex.Trip> patterns(String routeId) {
    var built = patterns;
    if (built == null) {
      synchronized (this) {
        if (patterns == null) {
          Map<String, Map<List<String>, TransitIndex.Trip>> byRoute = new HashMap<>();
          for (var t : index.trips.values()) {
            if (t.visits().isEmpty()) continue;
            var key = t.visits().stream().map(TransitIndex.Visit::stopId).toList();
            byRoute.computeIfAbsent(t.routeId(), k -> new HashMap<>()).putIfAbsent(key, t);
          }
          Map<String, List<TransitIndex.Trip>> out = new HashMap<>();
          byRoute.forEach((r, m) -> out.put(r, List.copyOf(m.values())));
          patterns = out;
        }
        built = patterns;
      }
    }
    return built.getOrDefault(routeId, List.of());
  }

  /**
   * Progress of a vehicle without a feed stop sequence. Uses the feed's trip when the static GTFS
   * has it; otherwise the stop patterns of the vehicle's (direction-specific) route, accepted only
   * when exactly one distinct pattern places the bus on its path.
   */
  Progress locateVehicle(RealtimeSnapshot.Vehicle v, TransitIndex.Route route) {
    var trip = index.trips.get(v.tripId());
    if (trip != null) return locate(v, trip);
    if (route == null) return null;
    Progress found = null;
    for (var pattern : patterns(route.id())) {
      var p = locate(v, pattern);
      if (p == null) continue;
      if (found != null) return null; // ambiguous between patterns
      found = p;
    }
    return found;
  }

  /**
   * The stop the vehicle is at / heading to. Prefers the feed's current_stop_sequence; when the
   * feed omits it, infers the next stop from the GPS position on the route's stop path.
   */
  Next nextStop(RealtimeSnapshot.Vehicle v) {
    if (v == null) return null;
    var trip = index.trips.get(v.tripId());
    TransitIndex.Stop stop = null;
    String source;
    if (v.sequence() != null) {
      if (trip == null) return null;
      var visit =
          trip.visits().stream().filter(x -> x.sequence() >= v.sequence()).findFirst().orElse(null);
      if (visit == null) return null;
      stop = index.stops.get(visit.stopId());
      source = SOURCE_FEED;
    } else {
      var progress = locateVehicle(v, transit.route(v));
      if (progress == null) return null;
      for (int i = 0; i < progress.cumulative().length; i++)
        if (progress.cumulative()[i] > progress.along() + PASSED_TOLERANCE_METERS) {
          stop = index.stops.get(progress.visits().get(i).stopId());
          break;
        }
      source = SOURCE_INFERRED;
    }
    if (stop == null) return null;
    double dist = Geometry.distance(v.lat(), v.lng(), stop.lat(), stop.lng());
    Double eta = null;
    if (dist <= MAX_NEXT_STOP_METERS && usableSpeed(v)) eta = dist / 1000.0 / v.speedKmh() * 60;
    return new Next(stop, Math.round(dist * 10.0) / 10.0, eta, source);
  }

  private static boolean usableSpeed(RealtimeSnapshot.Vehicle v) {
    return v.speedKmh() != null && v.speedKmh() >= MIN_ESTIMATE_SPEED_KMH;
  }

  /**
   * Minutes to reach {@code target} by summing straight-line hops through the trip's remaining
   * stops at the vehicle's current speed. Ignores road shape, traffic and dwell time, so it is
   * labelled a rough estimate and is only offered when the feed gives the vehicle's stop sequence
   * and a usable speed.
   */
  Double chainMinutes(
      RealtimeSnapshot.Vehicle v, TransitIndex.Trip trip, TransitIndex.Visit target) {
    if (v.sequence() == null || !usableSpeed(v) || target.sequence() < v.sequence()) return null;
    double dist = 0, lat = v.lat(), lng = v.lng();
    boolean any = false;
    for (var visit : trip.visits()) {
      if (visit.sequence() < v.sequence()) continue;
      if (visit.sequence() > target.sequence()) break;
      var s = index.stops.get(visit.stopId());
      if (s == null) return null;
      dist += Geometry.distance(lat, lng, s.lat(), s.lng());
      lat = s.lat();
      lng = s.lng();
      any = true;
    }
    if (!any) return null;
    double minutes = dist / 1000.0 / v.speedKmh() * 60;
    return minutes <= MAX_ESTIMATE_MINUTES ? minutes : null;
  }

  static final String CHAIN_LABEL =
      "Rough estimate: straight-line distance between stops at current speed; ignores traffic and"
          + " dwell time";

  List<Upcoming> upcoming(String stopId, Set<String> eligible, List<String> warnings) {
    var arrivals = transit.arrivals(stopId);
    for (String w : arrivals.warnings()) if (!warnings.contains(w)) warnings.add(w);
    Map<String, RealtimeSnapshot.Vehicle> vehicles = new HashMap<>();
    for (var v : transit.liveVehicles()) vehicles.put(v.id(), v);

    List<Upcoming> out = new ArrayList<>();
    Set<String> handled = new HashSet<>();
    // 1. Whatever the existing arrival logic can resolve (TripUpdates, shapes, stop sequence).
    for (var a : arrivals.arrivals()) {
      if (!eligible.contains(a.routeNumber())) continue;
      handled.add(a.vehicleId());
      if (a.etaMinutes() != null) {
        boolean tripUpdate = "TRIP_UPDATE".equals(a.predictionAvailability());
        out.add(
            new Upcoming(
                a.routeNumber(), a.vehicleId(), a.etaMinutes(), a.predictionAvailability(),
                tripUpdate
                    ? "Live prediction (GTFS-Realtime TripUpdates)"
                    : "Estimate from route geometry and live speed",
                a.reason(), a.remainingStops(), a.lastGpsUpdate()));
        continue;
      }
      Double chain = null;
      var v = vehicles.get(a.vehicleId());
      var trip = index.trips.get(a.tripId());
      if (v != null && trip != null && v.sequence() != null) {
        var target =
            trip.visits().stream()
                .filter(x -> x.stopId().equals(stopId) && x.sequence() >= v.sequence())
                .findFirst()
                .orElse(null);
        if (target != null) chain = chainMinutes(v, trip, target);
      }
      out.add(
          chain != null
              ? new Upcoming(a.routeNumber(), a.vehicleId(), chain, "STOP_CHAIN_ESTIMATE",
                  CHAIN_LABEL, null, a.remainingStops(), a.lastGpsUpdate())
              : new Upcoming(a.routeNumber(), a.vehicleId(), null, "UNAVAILABLE",
                  "ETA unavailable", a.reason(), a.remainingStops(), a.lastGpsUpdate()));
    }

    // 2. Feeds without current_stop_sequence: locate the bus on its trip's stop path.
    Set<String> serving = index.routesAtStop.getOrDefault(stopId, Set.of());
    for (var v : vehicles.values()) {
      if (handled.contains(v.id())
          || v.sequence() != null
          || !RealtimeSnapshot.fresh(v.timestamp())
          || live.cancelled(v.tripId())) continue;
      var route = transit.route(v);
      if (route == null || route.number() == null || !eligible.contains(route.number())) continue;
      if (!serving.contains(route.id())) continue;
      var progress = locateVehicle(v, route);
      if (progress == null) continue;
      int target = progress.indexOf(stopId);
      if (target < 0) continue;
      double remaining = progress.cumulative()[target] - progress.along();
      if (remaining < -PASSED_TOLERANCE_METERS) continue; // already passed this stop
      remaining = Math.max(0, remaining);
      int stopsBetween = 0;
      for (int i = 0; i < target; i++)
        if (progress.cumulative()[i] > progress.along() + PASSED_TOLERANCE_METERS) stopsBetween++;
      Double eta = usableSpeed(v) ? remaining / 1000.0 / v.speedKmh() * 60 : null;
      if (eta != null && eta > MAX_ESTIMATE_MINUTES) continue; // too far to call "upcoming"
      out.add(
          eta != null
              ? new Upcoming(route.number(), v.id(), eta, "STOP_CHAIN_ESTIMATE",
                  CHAIN_LABEL + "; position matched to the route's stops",
                  null, stopsBetween, v.timestamp())
              : new Upcoming(route.number(), v.id(), null, "UNAVAILABLE", "ETA unavailable",
                  "Bus is stationary or its speed is unknown.", stopsBetween, v.timestamp()));
    }

    out.sort(
        Comparator.comparing(Upcoming::etaMinutes, Comparator.nullsLast(Double::compareTo))
            .thenComparing(Upcoming::routeNumber));
    return out;
  }

  public RegionalStops nearbyStops(
      double lat, double lng, double radiusMeters, int limit, Filter filter) {
    double radius = Math.max(100, Math.min(5000, radiusMeters));
    int cap = Math.max(1, Math.min(limit, 20));
    Set<String> eligible = eligibleNumbers(filter);
    List<String> warnings = new ArrayList<>();
    List<RegionalStop> stops = new ArrayList<>();
    for (var s : index.nearby(lat, lng, radius)) {
      if (stops.size() >= cap) break;
      List<String> numbers =
          index.routesAtStop.getOrDefault(s.id(), Set.of()).stream()
              .map(index.routes::get)
              .filter(Objects::nonNull)
              .map(TransitIndex.Route::number)
              .filter(n -> n != null && eligible.contains(n))
              .distinct()
              .sorted()
              .toList();
      if (numbers.isEmpty()) continue;
      double dist = Geometry.distance(lat, lng, s.lat(), s.lng());
      var upcoming = upcoming(s.id(), Set.copyOf(numbers), warnings);
      stops.add(
          new RegionalStop(
              s.id(), s.name(), s.lat(), s.lng(),
              Math.round(dist * 10.0) / 10.0,
              Math.round(dist / (5000.0 / 60.0) * 10.0) / 10.0,
              "STRAIGHT_LINE_ESTIMATE",
              numbers,
              upcoming.size(),
              upcoming.stream().limit(5).toList()));
    }
    return new RegionalStops(stops, radius, transit.feedAvailable(), filter.name(), warnings);
  }
}
