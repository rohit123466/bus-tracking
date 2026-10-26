package com.dtc.bus_tracker.live;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Route-based North / North-West Delhi filtering for the Live Map.
 *
 * <p>A public route number is <em>eligible</em> when any GTFS route carrying that number has at
 * least one stop inside the configured region box ({@link TransitIndex#computeRegions()}). The
 * Live Map additionally shows only buses currently inside the selected area, and only stops that
 * lie inside it. Vehicle positions come only from the realtime snapshot; nothing here simulates or
 * invents a position or an arrival time.
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
  /**
   * Assumed average speed of a Delhi city bus including stops and signals, used for an approaching
   * bus that is momentarily stopped or slow. Labelled as such wherever it is shown.
   */
  static final double TYPICAL_BUS_SPEED_KMH = 17;
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
  public static final String SOURCE_LEARNED = "LEARNED_ROUTE_PATH";

  /**
   * How far the route number shown for a bus is backed by the data. The live feed mostly reports
   * trip IDs the static timetable lacks, so the number usually comes from the feed's route_id via
   * routes.txt, a mapping that may be out of date. The GPS fix is compared with that route's
   * timetable stops to say whether the label is plausible; it never picks a different route.
   */
  /** The feed's trip_id is in the timetable and belongs to this route. */
  public static final String CHECK_TRIP = "TRIP_MATCH";
  /** The bus is on DTC's current official path for the route number ({@link OfficialRoutes}). */
  public static final String CHECK_ON_OFFICIAL = "ON_OFFICIAL_ROUTE";
  /** The bus is within {@link #ROUTE_CHECK_METERS} of the route's timetable stop path. */
  public static final String CHECK_ON_ROUTE = "ON_ROUTE";
  /** Farther than that from every stop pattern: depot, diversion or an outdated route_id. */
  public static final String CHECK_OFF_ROUTE = "OFF_ROUTE";
  /** The route has no timetable stops to compare the position with. */
  public static final String CHECK_NO_STOPS = "NO_STOPS";
  /** Lenient: straight lines between stops cut corners that the road goes around. */
  static final double ROUTE_CHECK_METERS = 500;

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
      String nextStopEtaBasis,
      /** One of the CHECK_* values: whether the GPS fix fits the route number shown. */
      String routeCheck,
      /** See {@link #likelyRoutes}; empty unless the route number shown is OFF_ROUTE. */
      List<String> likelyRoutes) {}

  public record RegionalBuses(
      List<RegionalBus> buses,
      List<String> warnings,
      long fetchedAt,
      boolean feedAvailable,
      String region,
      int eligibleRouteCount,
      int excludedByRegion,
      /** Buses on eligible routes hidden because they are currently outside the area. */
      int excludedOutsideArea) {}

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
      long lastGpsUpdate,
      /** Current GPS position, wherever in Delhi the bus is; null when the feed has none. */
      Double busLatitude,
      Double busLongitude,
      Double speedKmh,
      /** Distance still to travel to the stop along the route's stops; null when unknown. */
      Double distanceMeters,
      /** One of the CHECK_* values: whether the GPS fix fits the route number shown. */
      String routeCheck,
      /** See {@link #likelyRoutes}; empty unless the route number shown is OFF_ROUTE. */
      List<String> likelyRoutes) {}

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
  private final RouteLearner learner;
  private final OfficialRoutes official;
  private final RouteIdentifier identifier;

  /** Without DTC's official route list: checks use the GTFS timetable only. */
  public RegionService(
      TransitIndex index, LiveTransitService transit, RealtimeSnapshot live, RouteLearner learner) {
    this(index, transit, live, learner, new OfficialRoutes(), null);
  }

  @Autowired
  public RegionService(
      TransitIndex index, LiveTransitService transit, RealtimeSnapshot live, RouteLearner learner,
      OfficialRoutes official, RouteIdentifier identifier) {
    this.index = index;
    this.transit = transit;
    this.live = live;
    this.learner = learner;
    this.official = official;
    this.identifier = identifier == null ? new RouteIdentifier(learner, official) : identifier;
  }

  /**
   * Public route number -> union of regions across every GTFS route that carries it, from its
   * static GTFS stops, from the stops on its learned (actually driven) path, and from its stops in
   * DTC's current official route list.
   */
  Map<String, EnumSet<TransitIndex.Region>> regionsByNumber() {
    Map<String, EnumSet<TransitIndex.Region>> out = new HashMap<>();
    officialRegions().forEach((n, r) ->
        out.computeIfAbsent(n, k -> EnumSet.noneOf(TransitIndex.Region.class)).addAll(r));
    for (var e : index.routeRegions.entrySet()) {
      var route = index.routes.get(e.getKey());
      if (route == null || route.number() == null) continue;
      out.computeIfAbsent(route.number(), k -> EnumSet.noneOf(TransitIndex.Region.class))
          .addAll(e.getValue());
    }
    for (var learned : learner.routes()) {
      var route = index.routes.get(learned.routeId());
      if (route == null || route.number() == null) continue;
      var here = learnedRegions(learned);
      if (!here.isEmpty())
        out.computeIfAbsent(route.number(), k -> EnumSet.noneOf(TransitIndex.Region.class))
            .addAll(here);
    }
    return out;
  }

  // Both from static data, so built once.
  private volatile Map<String, String> gtfsNumberByKey;
  private volatile Map<String, EnumSet<TransitIndex.Region>> officialRegions;

  /**
   * The number as the GTFS spells it when it has the same route ("408 STL" -> "408STL"), so an
   * official number can be selected and searched like any other; otherwise DTC's spelling.
   */
  String canonical(String officialNumber) {
    var byKey = gtfsNumberByKey;
    if (byKey == null) {
      Map<String, String> built = new HashMap<>();
      for (var r : index.routes.values())
        if (r.number() != null) built.putIfAbsent(OfficialRoutes.key(r.number()), r.number());
      gtfsNumberByKey = byKey = built;
    }
    return byKey.getOrDefault(OfficialRoutes.key(officialNumber), officialNumber);
  }

  private Map<String, EnumSet<TransitIndex.Region>> officialRegions() {
    var built = officialRegions;
    if (built == null) {
      built = new HashMap<>();
      for (var p : official.paths())
        for (var pt : p.points()) {
          var here = index.regionsAt(pt.lat(), pt.lng());
          if (!here.isEmpty())
            built.computeIfAbsent(canonical(p.number()), k -> EnumSet.noneOf(TransitIndex.Region.class))
                .addAll(here);
        }
      officialRegions = built;
    }
    return built;
  }

  private record CachedRegions(RouteLearner.LearnedRoute path, EnumSet<TransitIndex.Region> regions) {}

  /** route_id -> regions of its learned path's stops; recomputed only when the path is replaced. */
  private final Map<String, CachedRegions> learnedRegionCache = new java.util.concurrent.ConcurrentHashMap<>();

  EnumSet<TransitIndex.Region> learnedRegions(RouteLearner.LearnedRoute learned) {
    var cached = learnedRegionCache.get(learned.routeId());
    if (cached != null && cached.path() == learned) return cached.regions();
    EnumSet<TransitIndex.Region> here = EnumSet.noneOf(TransitIndex.Region.class);
    for (var ls : learned.stops()) {
      var s = index.stops.get(ls.stopId());
      if (s != null) here.addAll(index.regionsAt(s.lat(), s.lng()));
    }
    learnedRegionCache.put(learned.routeId(), new CachedRegions(learned, here));
    return here;
  }

  /** GTFS route_ids whose learned path passes the stop. */
  Set<String> learnedRoutesAt(String stopId) {
    Set<String> out = new HashSet<>();
    for (var learned : learner.routes())
      if (learned.stop(stopId) != null) out.add(learned.routeId());
    return out;
  }

  record OnPath(RouteLearner.LearnedRoute route, RouteLearner.Located at) {}

  /**
   * The vehicle's position on its route's learned path; null when the route has none, the bus is
   * not on it, or the bus is moving against the path's direction.
   */
  OnPath onLearnedPath(RealtimeSnapshot.Vehicle v, TransitIndex.Route route) {
    if (route == null) return null;
    var learned = learner.route(route.id());
    if (learned == null) return null;
    var at = learned.locate(v.lat(), v.lng());
    if (at.offset() > RouteLearner.MAX_BUS_OFFSET_METERS) return null;
    Double heading =
        v.bearing() != null && v.speedKmh() != null && v.speedKmh() > 3
            ? v.bearing()
            : learner.heading(v.id());
    if (heading != null) {
      double delta = Math.abs(heading - at.bearing());
      if (Math.min(delta, 360 - delta) > 100) return null;
    }
    return new OnPath(learned, at);
  }

  /** Minutes the learned drive took between two positions; null beyond the upcoming horizon. */
  static Double learnedMinutes(RouteLearner.LearnedRoute route, double fromAlong, double toAlong) {
    double minutes = Math.max(0, route.secondsAt(toAlong) - route.secondsAt(fromAlong)) / 60;
    return minutes <= MAX_ESTIMATE_MINUTES ? minutes : null;
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
        "A route is eligible when at least one of its GTFS stops, or a stop on the path its buses"
            + " were seen driving, lies inside the region box.");
  }

  /** True when the point lies inside the area(s) the filter selects. */
  boolean insideArea(double lat, double lng, Filter filter) {
    var here = index.regionsAt(lat, lng);
    return switch (filter) {
      case NORTH -> here.contains(TransitIndex.Region.NORTH);
      case NORTH_WEST -> here.contains(TransitIndex.Region.NORTH_WEST);
      case ANY -> !here.isEmpty();
    };
  }

  /** Live buses on eligible routes that are currently inside the selected area. */
  public RegionalBuses buses(Filter filter) {
    return buses(filter, "", true);
  }

  /**
   * Live buses on region-eligible routes, optionally narrowed to one public route number. With
   * {@code insideAreaOnly}, a bus must also be inside the selected area right now; otherwise it is
   * kept wherever in Delhi it is.
   */
  public RegionalBuses buses(Filter filter, String routeNumber, boolean insideAreaOnly) {
    String requested = routeNumber == null ? "" : routeNumber.trim();
    var all = transit.buses(requested);
    var byNumber = regionsByNumber();
    Set<String> eligible = eligibleNumbers(filter);
    Map<String, RealtimeSnapshot.Vehicle> vehicles = new HashMap<>();
    for (var v : transit.liveVehicles()) vehicles.put(v.id(), v);

    // A search for a number also finds buses whose feed number is stale but whose drive matches
    // that number's current official path.
    List<LiveTransitService.Bus> candidates = new ArrayList<>(all.buses());
    boolean likelyAdded = false;
    if (!requested.isEmpty() && !official.isEmpty()) {
      Set<String> listed = new HashSet<>();
      for (var b : candidates) listed.add(b.vehicleId());
      String key = OfficialRoutes.key(requested);
      for (var b : transit.buses("").buses()) {
        if (listed.contains(b.vehicleId())) continue;
        var v = vehicles.get(b.vehicleId());
        var route = index.routes.get(b.routeId());
        if (v == null || route == null) continue;
        if (likelyRoutes(v, route, routeCheck(v, route)).stream()
            .anyMatch(n -> OfficialRoutes.key(n).equals(key))) {
          candidates.add(b);
          likelyAdded = true;
        }
      }
    }

    List<RegionalBus> out = new ArrayList<>();
    int excluded = 0, outsideArea = 0;
    for (var b : candidates) {
      var vehicle = vehicles.get(b.vehicleId());
      var route = index.routes.get(b.routeId());
      String check = vehicle == null ? CHECK_NO_STOPS : routeCheck(vehicle, route);
      var likely = vehicle == null ? List.<String>of() : likelyRoutes(vehicle, route, check);
      if (!eligible.contains(b.routeCode()) && likely.stream().noneMatch(eligible::contains)) {
        excluded++;
        continue;
      }
      if (insideAreaOnly && !insideArea(b.busLatitude(), b.busLongitude(), filter)) {
        outsideArea++;
        continue;
      }
      EnumSet<TransitIndex.Region> here = EnumSet.noneOf(TransitIndex.Region.class);
      for (var n : concat(b.routeCode(), likely)) {
        var r = byNumber.get(n);
        if (r != null) here.addAll(r);
      }
      var regions = here.stream().map(Enum::name).toList();
      var next = nextStop(vehicle);
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
              next == null || next.etaMinutes() == null ? null : next.basis(),
              check, likely));
    }
    List<String> warnings = new ArrayList<>(all.warnings());
    // transit.buses() says no bus carries the number; some do, by their likely route.
    if (likelyAdded) warnings.removeIf(w -> w.startsWith("No GTFS public-route mapping"));
    long offRoute = out.stream().filter(b -> CHECK_OFF_ROUTE.equals(b.routeCheck())).count();
    if (offRoute > 0)
      warnings.add(
          offRoute + " shown buses are more than " + (int) ROUTE_CHECK_METERS + " m from every"
              + " stop of the route number the feed gives them, so that number is"
              + " unconfirmed (depot, diversion, or an outdated route mapping).");
    long likelyShown = out.stream().filter(b -> !b.likelyRoutes().isEmpty()).count();
    if (likelyShown > 0)
      warnings.add(
          likelyShown + " of those have a likely route: the path their route's buses were seen"
              + " driving lies on that number's path in DTC's current route list. This is an"
              + " estimate from GPS, not a number the feed reports.");
    long unknownTrips = out.stream().filter(b -> !index.trips.containsKey(b.tripId())).count();
    if (unknownTrips > 0)
      warnings.add(
          unknownTrips + " shown buses report trip IDs missing from the static GTFS; their next"
              + " stop is inferred from the route's stop pattern where unambiguous.");
    if (index.routeRegions.isEmpty())
      warnings.add("No GTFS route has a stop inside the configured North/North-West boxes.");
    return new RegionalBuses(
        out, warnings, all.fetchedAt(), all.feedAvailable(), filter.name(), eligible.size(),
        excluded, outsideArea);
  }

  record Next(
      TransitIndex.Stop stop, double distance, Double etaMinutes, String source, String basis) {}

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

  /** route_id -> each stop pattern as points, for {@link #routeCheck}. Static data, so cached. */
  private final Map<String, List<List<TransitIndex.Point>>> patternPaths =
      new java.util.concurrent.ConcurrentHashMap<>();

  private List<List<TransitIndex.Point>> patternPaths(String routeId) {
    return patternPaths.computeIfAbsent(routeId, id -> {
      List<List<TransitIndex.Point>> out = new ArrayList<>();
      for (var trip : patterns(id)) {
        List<TransitIndex.Point> points = new ArrayList<>();
        for (var visit : trip.visits()) {
          var s = index.stops.get(visit.stopId());
          if (s != null) points.add(new TransitIndex.Point(s.lat(), s.lng(), visit.sequence()));
        }
        if (!points.isEmpty()) out.add(List.copyOf(points));
      }
      return List.copyOf(out);
    });
  }

  private static List<String> concat(String first, List<String> rest) {
    List<String> out = new ArrayList<>(rest.size() + 1);
    out.add(first);
    out.addAll(rest);
    return out;
  }

  /**
   * One of the CHECK_* values for the route number shown for {@code v}. Only says whether the GPS
   * fix is consistent with that route's official path or timetable stops; a nearby different
   * route is never substituted here, because one position does not identify the route a bus is
   * running (see {@link #likelyRoutes} for that).
   */
  String routeCheck(RealtimeSnapshot.Vehicle v, TransitIndex.Route route) {
    if (route == null) return CHECK_NO_STOPS;
    var trip = index.trips.get(v.tripId());
    if (trip != null && trip.routeId().equals(route.id())) return CHECK_TRIP;
    boolean listed = route.number() != null && !official.paths(route.number()).isEmpty();
    if (listed && official.onRoute(route.number(), v.lat(), v.lng())) return CHECK_ON_OFFICIAL;
    var paths = patternPaths(route.id());
    if (paths.isEmpty()) return listed ? CHECK_OFF_ROUTE : CHECK_NO_STOPS;
    for (var path : paths) {
      double offset =
          path.size() == 1
              ? Geometry.distance(v.lat(), v.lng(), path.get(0).lat(), path.get(0).lng())
              : Geometry.project(path, v.lat(), v.lng()).offset();
      if (offset <= ROUTE_CHECK_METERS) return CHECK_ON_ROUTE;
    }
    return CHECK_OFF_ROUTE;
  }

  /**
   * For a bus whose route number does not fit ({@link #CHECK_OFF_ROUTE}): the route numbers, at
   * most {@link RouteIdentifier#MAX_CANDIDATES}, whose current official path contains the path its
   * route ID's buses were seen driving, and on which this bus is right now. Empty otherwise.
   */
  List<String> likelyRoutes(RealtimeSnapshot.Vehicle v, TransitIndex.Route route, String check) {
    if (!CHECK_OFF_ROUTE.equals(check) || route == null) return List.of();
    Set<String> out = new LinkedHashSet<>();
    for (String n : identifier.candidates(route.id()))
      if (official.onRoute(n, v.lat(), v.lng())) out.add(canonical(n));
    return List.copyOf(out);
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
    // A learned path reflects where the route really runs; prefer it over the static stops.
    var on = v.sequence() == null ? onLearnedPath(v, transit.route(v)) : null;
    if (on != null) {
      double along = on.at().along();
      for (var ls : on.route().stops()) {
        if (ls.along() <= along + PASSED_TOLERANCE_METERS) continue;
        var s = index.stops.get(ls.stopId());
        if (s == null) continue;
        return new Next(
            s, Math.round((ls.along() - along) * 10.0) / 10.0,
            learnedMinutes(on.route(), along, ls.along()), SOURCE_LEARNED,
            "LEARNED_ROUTE_ESTIMATE");
      }
      return null; // past the last stop on the learned path
    }
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
    return new Next(stop, Math.round(dist * 10.0) / 10.0, eta, source, "STOP_CHAIN_ESTIMATE");
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

  static final String LEARNED_LABEL =
      "Estimate from how long a bus on this route recently took for the same stretch (route path"
          + " learned from live GPS)";

  static final String TYPICAL_SPEED_LABEL =
      "Rough estimate: bus is stopped or slow right now, so its distance along the route is divided"
          + " by a typical city-bus speed of " + (int) TYPICAL_BUS_SPEED_KMH + " km/h";

  /**
   * Builds an {@link Upcoming}, attaching the vehicle's live position and how well that position
   * fits the route number when the vehicle is known.
   */
  Upcoming upcomingOf(
      String routeNumber, String vehicleId, Double eta, String availability, String label,
      String reason, Integer remainingStops, long lastGps, RealtimeSnapshot.Vehicle v,
      Double distanceMeters) {
    var route = v == null ? null : transit.route(v);
    String check = v == null ? CHECK_NO_STOPS : routeCheck(v, route);
    return new Upcoming(
        routeNumber, vehicleId, eta, availability, label, reason, remainingStops, lastGps,
        v == null ? null : v.lat(), v == null ? null : v.lng(), v == null ? null : v.speedKmh(),
        distanceMeters == null ? null : Math.round(distanceMeters * 10.0) / 10.0,
        check, v == null ? List.of() : likelyRoutes(v, route, check));
  }

  /**
   * Per-request cache of the live vehicles grouped by route and of where each sits on its route.
   * Neither depends on the stop, so looking up many nearby stops resolves every vehicle once and
   * each stop only visits the vehicles of routes that serve it.
   */
  final class Memo {
    private final Map<String, Optional<OnPath>> learned = new HashMap<>();
    private final Map<String, Optional<Progress>> located = new HashMap<>();
    private Map<String, RealtimeSnapshot.Vehicle> vehicles;
    /** GTFS route_id -> fresh, not-cancelled vehicles resolved to it. */
    private Map<String, List<RealtimeSnapshot.Vehicle>> byRoute;

    private void build() {
      if (vehicles != null) return;
      vehicles = new HashMap<>();
      byRoute = new HashMap<>();
      for (var v : transit.liveVehicles()) {
        vehicles.put(v.id(), v);
        if (!RealtimeSnapshot.fresh(v.timestamp()) || live.cancelled(v.tripId())) continue;
        var route = transit.route(v);
        if (route == null || route.number() == null) continue;
        byRoute.computeIfAbsent(route.id(), k -> new ArrayList<>()).add(v);
      }
    }

    Map<String, RealtimeSnapshot.Vehicle> vehicles() {
      build();
      return vehicles;
    }

    List<RealtimeSnapshot.Vehicle> onRoute(String routeId) {
      build();
      return byRoute.getOrDefault(routeId, List.of());
    }

    OnPath onLearnedPath(RealtimeSnapshot.Vehicle v, TransitIndex.Route route) {
      return learned
          .computeIfAbsent(v.id(), k -> Optional.ofNullable(RegionService.this.onLearnedPath(v, route)))
          .orElse(null);
    }

    Progress locateVehicle(RealtimeSnapshot.Vehicle v, TransitIndex.Route route) {
      return located
          .computeIfAbsent(v.id(), k -> Optional.ofNullable(RegionService.this.locateVehicle(v, route)))
          .orElse(null);
    }
  }

  List<Upcoming> upcoming(String stopId, Set<String> eligible, List<String> warnings) {
    return upcoming(stopId, eligible, warnings, new Memo());
  }

  List<Upcoming> upcoming(String stopId, Set<String> eligible, List<String> warnings, Memo memo) {
    var arrivals = transit.arrivals(stopId);
    for (String w : arrivals.warnings()) if (!warnings.contains(w)) warnings.add(w);
    var vehicles = memo.vehicles();

    List<Upcoming> out = new ArrayList<>();
    Set<String> handled = new HashSet<>();
    // 1. Whatever the existing arrival logic can resolve (TripUpdates, shapes, stop sequence).
    for (var a : arrivals.arrivals()) {
      if (!eligible.contains(a.routeNumber())) continue;
      handled.add(a.vehicleId());
      if (a.etaMinutes() != null) {
        boolean tripUpdate = "TRIP_UPDATE".equals(a.predictionAvailability());
        out.add(
            upcomingOf(
                a.routeNumber(), a.vehicleId(), a.etaMinutes(), a.predictionAvailability(),
                tripUpdate
                    ? "Live prediction (GTFS-Realtime TripUpdates)"
                    : "Estimate from route geometry and live speed",
                a.reason(), a.remainingStops(), a.lastGpsUpdate(), vehicles.get(a.vehicleId()),
                null));
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
              ? upcomingOf(a.routeNumber(), a.vehicleId(), chain, "STOP_CHAIN_ESTIMATE",
                  CHAIN_LABEL, null, a.remainingStops(), a.lastGpsUpdate(), v, null)
              : upcomingOf(a.routeNumber(), a.vehicleId(), null, "UNAVAILABLE",
                  "ETA unavailable", a.reason(), a.remainingStops(), a.lastGpsUpdate(), v, null));
    }

    // 2. Learned paths: where the route really runs and how long its buses took, from watching
    //    them drive. The bus must be on the path, heading along it, with the stop still ahead.
    for (String routeId : learnedRoutesAt(stopId)) {
      var route = index.routes.get(routeId);
      if (route == null || route.number() == null || !eligible.contains(route.number())) continue;
      var learned = learner.route(routeId);
      var target = learned == null ? null : learned.stop(stopId);
      if (target == null) continue;
      for (var v : memo.onRoute(routeId)) {
        if (handled.contains(v.id())) continue;
        var on = memo.onLearnedPath(v, route);
        if (on == null) continue;
        double along = on.at().along();
        double remaining = target.along() - along;
        if (remaining < -PASSED_TOLERANCE_METERS) continue; // already passed this stop
        Double eta = learnedMinutes(learned, along, target.along());
        if (eta == null) continue; // too far to call "upcoming"
        handled.add(v.id());
        out.add(
            upcomingOf(route.number(), v.id(), eta, "LEARNED_ROUTE_ESTIMATE", LEARNED_LABEL, null,
                learned.stopsBetween(along + PASSED_TOLERANCE_METERS, target.along()),
                v.timestamp(), v, Math.max(0, remaining)));
      }
    }

    // 3. Feeds without current_stop_sequence: locate the bus on its trip's stop path.
    for (String routeId : index.routesAtStop.getOrDefault(stopId, Set.of())) {
      var route = index.routes.get(routeId);
      if (route == null || route.number() == null || !eligible.contains(route.number())) continue;
      for (var v : memo.onRoute(routeId)) {
        if (handled.contains(v.id()) || v.sequence() != null) continue;
        var progress = memo.locateVehicle(v, route);
        if (progress == null) continue;
        int target = progress.indexOf(stopId);
        if (target < 0) continue;
        double remaining = progress.cumulative()[target] - progress.along();
        if (remaining < -PASSED_TOLERANCE_METERS) continue; // already passed this stop
        remaining = Math.max(0, remaining);
        int stopsBetween = 0;
        for (int i = 0; i < target; i++)
          if (progress.cumulative()[i] > progress.along() + PASSED_TOLERANCE_METERS) stopsBetween++;
        // Moving: current speed. Stopped or slow (signal, stop, jam): a typical city-bus speed.
        boolean moving = usableSpeed(v);
        double eta = remaining / 1000.0 / (moving ? v.speedKmh() : TYPICAL_BUS_SPEED_KMH) * 60;
        if (eta > MAX_ESTIMATE_MINUTES) continue; // too far to call "upcoming"
        out.add(
            moving
                ? upcomingOf(route.number(), v.id(), eta, "STOP_CHAIN_ESTIMATE",
                    CHAIN_LABEL + "; position matched to the route's stops",
                    null, stopsBetween, v.timestamp(), v, remaining)
                : upcomingOf(route.number(), v.id(), eta, "TYPICAL_SPEED_ESTIMATE",
                    TYPICAL_SPEED_LABEL, "Bus is stationary or its speed is unknown.", stopsBetween,
                    v.timestamp(), v, remaining));
      }
    }

    out.sort(
        Comparator.comparing(Upcoming::etaMinutes, Comparator.nullsLast(Double::compareTo))
            .thenComparing(Upcoming::routeNumber));
    return out;
  }

  public RegionalStops nearbyStops(
      double lat, double lng, double radiusMeters, int limit, Filter filter) {
    double radius = Math.max(100, Math.min(5000, radiusMeters));
    // Up to 60 stops: within 1 km of a busy junction there are often 30+ (one per road side).
    int cap = Math.max(1, Math.min(limit, 60));
    Set<String> eligible = eligibleNumbers(filter);
    List<String> warnings = new ArrayList<>();
    List<RegionalStop> stops = new ArrayList<>();
    var memo = new Memo();
    for (var s : index.nearby(lat, lng, radius)) {
      if (stops.size() >= cap) break;
      if (!insideArea(s.lat(), s.lng(), filter)) continue;
      Set<String> serving = new HashSet<>(index.routesAtStop.getOrDefault(s.id(), Set.of()));
      serving.addAll(learnedRoutesAt(s.id()));
      List<String> numbers =
          serving.stream()
              .map(index.routes::get)
              .filter(Objects::nonNull)
              .map(TransitIndex.Route::number)
              .filter(n -> n != null && eligible.contains(n))
              .distinct()
              .sorted()
              .toList();
      if (numbers.isEmpty()) continue;
      double dist = Geometry.distance(lat, lng, s.lat(), s.lng());
      var upcoming = upcoming(s.id(), Set.copyOf(numbers), warnings, memo);
      stops.add(
          new RegionalStop(
              s.id(), s.name(), s.lat(), s.lng(),
              Math.round(dist * 10.0) / 10.0,
              Math.round(dist / (5000.0 / 60.0) * 10.0) / 10.0,
              "STRAIGHT_LINE_ESTIMATE",
              numbers,
              upcoming.size(),
              upcoming)); // every tracked arrival, so each route at the stop gets its times
    }
    return new RegionalStops(stops, radius, transit.feedAvailable(), filter.name(), warnings);
  }
}
