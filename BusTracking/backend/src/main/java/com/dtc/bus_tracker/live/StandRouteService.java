package com.dtc.bus_tracker.live;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Find buses from one bus stand to another": every route whose timetable stop sequence calls at
 * the pick-up stand and, later in the same sequence, at the destination stand.
 *
 * <p>Matching works on GTFS stop patterns, not on route numbers. In the bundled DTC/DoT feed each
 * route_id is one direction of a route number ({@code 102AUP}, {@code 102ADOWN}), and one route_id
 * may run several stop patterns (short workings, variants). A pattern matches only when a pick-up
 * stop comes before a destination stop in it, so a route running the other way never matches,
 * and the stops between the two are read from that same pattern.
 *
 * <p>Live buses are kept separate from routes: a route is listed whether or not any bus on it is
 * reporting GPS, and {@link #live} lists the buses currently mapped to a route's route_ids, with
 * an arrival estimate at the pick-up stop from {@link RegionService#upcoming} when one exists.
 * Nothing here invents a position, a time or a stop.
 */
@Service
public class StandRouteService {
  /** Stops within this walk of the selected stand may be used when the caller asks for it. */
  static final double MAX_WALK_METERS = 500;
  /**
   * Ranking weight of one extra stop ridden, in metres walked. Below the ~300 m between adjacent
   * stops, so a stop of the selected stand beats a nearby stop one stop along; a loop that passes
   * the stand again much later still loses to a nearby stop just before the destination.
   */
  static final double STOP_COST_METERS = 100;
  /** Ride times above this are not believable for one city bus ride and are ignored. */
  static final int MAX_RIDE_SECONDS = 4 * 3600;
  /** Shape snapping: a stop farther than this from its route's shape makes the shape unusable. */
  static final double SHAPE_SNAP_METERS = 150;
  static final int NEXT_DEPARTURES = 3;
  static final int CACHE_ENTRIES = 300;

  /** One-change journeys are looked for when fewer direct routes than this are found. */
  static final int TRANSFERS_WHEN_DIRECT_BELOW = 3;
  static final int MAX_TRANSFERS = 8;
  /** A route number appears at most this often as the first, and as the second, bus. */
  static final int MAX_PER_ROUTE_NUMBER = 2;
  /** Walk allowed between the stop you get off at and the stop you board the second bus at. */
  static final double TRANSFER_WALK_METERS = 200;
  /** A bus leg shorter than this (straight line) is quicker to walk; such changes are skipped. */
  static final double MIN_LEG_METERS = 800;
  /** Ranking only: a change costs time even when the next bus comes at once. Never shown. */
  static final double CHANGE_ALLOWANCE_MINUTES = 10;
  static final double WALK_KMH = 5;
  /** Leg options per stop considered when joining (quickest route numbers first). */
  static final int LEG_OPTIONS = 5;
  /** Ranking fallback when a pattern has no usable times. */
  static final double FALLBACK_MINUTES_PER_STOP = 1.5;

  public static final String STATUS_OK = "OK";
  public static final String STATUS_NO_ROUTES = "NO_ROUTES";
  public static final String STATUS_SAME_STAND = "SAME_STAND";
  public static final String STATUS_NO_ROUTES_AT_PICKUP = "NO_ROUTES_AT_PICKUP";
  public static final String STATUS_NO_ROUTES_AT_DESTINATION = "NO_ROUTES_AT_DESTINATION";

  /** Distance along the route's GTFS shape between the two stops. */
  public static final String DISTANCE_SHAPE = "ROUTE_SHAPE";
  /** Sum of straight lines between consecutive stops: a little short of the road distance. */
  public static final String DISTANCE_STOP_TO_STOP = "STOP_TO_STOP";
  /** Median scheduled ride time over the timetable's trips. */
  public static final String TIME_SCHEDULED = "SCHEDULED_GTFS";

  public static final String PICKUP_AHEAD = "HEADING_TO_PICKUP";
  public static final String PICKUP_PASSED = "PASSED_PICKUP";
  public static final String PICKUP_UNKNOWN = "POSITION_UNKNOWN";

  public static final String TIMETABLE_NOTE =
      "Routes, stop order and scheduled times come from the DTC/DoT GTFS timetable bundled with the"
          + " server. The timetable has one service pattern for every day, so scheduled times may"
          + " not match the buses actually running.";

  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  public record RouteStop(String id, String name, double lat, double lng) {}

  /** A timetable departure from the pick-up stop; never a live position. */
  public record Departure(String time, long epochSeconds, double minutesUntil) {}

  /**
   * One way to ride from the pick-up stand to the destination stand without changing bus.
   * {@code routeIds} has more than one entry only when several GTFS route_ids with the same
   * number run exactly the same stops between the two.
   */
  public record RouteMatch(
      String key,
      String routeNumber,
      List<String> routeIds,
      /** UP, DOWN or "" as published in the route name. */
      String direction,
      /** First and last stop of the route's stop pattern. */
      String origin,
      String terminus,
      RouteStop pickup,
      /** Straight-line walk from the selected stand; 0 when the stop belongs to it. */
      double pickupWalkMeters,
      boolean pickupAtSelectedStand,
      RouteStop destination,
      double destinationWalkMeters,
      boolean destinationAtSelectedStand,
      /** Pick-up to destination, both included, in travel order. */
      List<RouteStop> stops,
      int intermediateStopCount,
      /** The pick-up stop's position in the route's pattern, from 1. */
      int pickupStopNumber,
      int routeStopCount,
      Double distanceKm,
      /** {@link #DISTANCE_SHAPE} or {@link #DISTANCE_STOP_TO_STOP}. */
      String distanceBasis,
      /** Null when the timetable has no usable times for this stretch. */
      Double scheduledMinutes,
      String scheduledBasis,
      /** Timetable trips a day that run from the pick-up stop to the destination stop. */
      int tripsPerDay,
      String firstDeparture,
      String lastDeparture,
      List<Departure> nextDepartures,
      /** Distinct stop lists between the two stops among the route's patterns; 1 = all agree. */
      int variants,
      /** Buses on the live feed mapped to these route_ids right now. */
      int liveBusCount) {}

  /**
   * A journey with one change of bus. {@code first} ends at the stop you get off at, {@code second}
   * starts at the stop you board the next bus at (the same stop, or {@code transferWalkMeters}
   * away). Each leg has the fields of a direct match, including its live bus count.
   */
  public record Transfer(
      String key,
      RouteMatch first,
      RouteMatch second,
      double transferWalkMeters,
      /** Both legs' scheduled ride time plus the walk; excludes waiting. Null if a leg has no times. */
      Double totalMinutes,
      int totalStops) {}

  public record RouteSearch(
      String status,
      String message,
      StandService.Stand from,
      StandService.Stand to,
      double walkMeters,
      List<RouteMatch> routes,
      /** Journeys with one change, when fewer than 3 direct routes exist; quickest first. */
      List<Transfer> transfers,
      int routesWithLiveBuses,
      /** With no route found: how many run the other way (destination to pick-up). */
      int reverseRouteCount,
      boolean feedAvailable,
      long feedFetchedAt,
      long generatedAt,
      List<String> notes) {}

  /**
   * A live bus on a matched route. Field names follow the stand board's arrivals
   * ({@code etaMinutes}, {@code availability}, {@code lastGpsUpdate}, ...).
   */
  public record LiveBus(
      String vehicleId,
      String routeNumber,
      String routeId,
      Double busLatitude,
      Double busLongitude,
      Double speedKmh,
      long lastGpsUpdate,
      long gpsAgeSeconds,
      /** "Near <stop>" for the GPS position; null when no stop is within 500 m. */
      String currentLocation,
      String nextStopId,
      String nextStopName,
      Double nextStopDistanceMeters,
      /** {@link #PICKUP_AHEAD}, {@link #PICKUP_PASSED} or {@link #PICKUP_UNKNOWN}. */
      String pickupState,
      Double etaMinutes,
      String availability,
      String etaLabel,
      Double distanceToPickupMeters,
      Integer stopsToPickup,
      String routeCheck,
      List<String> likelyRoutes) {}

  public record RouteLive(
      String pickupStopId,
      String pickupStopName,
      List<String> routeIds,
      List<LiveBus> buses,
      int headingToPickup,
      boolean feedAvailable,
      long feedFetchedAt,
      long generatedAt,
      /** Set when no bus is listed, saying why. */
      String message,
      List<String> warnings) {}

  /** One distinct stop sequence of a route_id, with every timetable trip that runs it. */
  record Pattern(int id, String routeId, String[] stops, List<TransitIndex.Trip> trips) {}

  record PatternIndex(List<Pattern> patterns, Map<String, int[]> atStop, Map<String, List<Pattern>> byRoute) {}

  /** Static answer for one stand pair: direct routes, and one-change journeys when few are direct. */
  record Plan(List<Static> direct, List<StaticTransfer> transfers) {}

  /** The best ride on one pattern: board at index {@code from}, alight at {@code to}. */
  record Ride(Pattern pattern, int from, int to, double cost) {}

  /** The request-independent part of a match; live counts and next departures are added later. */
  record Static(RouteMatch base, int[] departures) {}

  private final TransitIndex index;
  private final StandService stands;
  private final RegionService regions;
  private final LiveTransitService transit;
  private final RealtimeSnapshot live;

  private volatile PatternIndex patternIndex;
  private final Map<String, double[]> shapeLengths = new ConcurrentHashMap<>();
  private final Map<String, Plan> cache = new ConcurrentHashMap<>();

  public StandRouteService(
      TransitIndex index, StandService stands, RegionService regions, LiveTransitService transit,
      RealtimeSnapshot live) {
    this.index = index;
    this.stands = stands;
    this.regions = regions;
    this.transit = transit;
    this.live = live;
  }

  // ── Pattern index ────────────────────────────────────────────────────────

  PatternIndex patternIndex() {
    var built = patternIndex;
    if (built == null) {
      synchronized (this) {
        if (patternIndex == null) patternIndex = buildPatterns();
        built = patternIndex;
      }
    }
    return built;
  }

  /** One pass over the trips: group by (route_id, stop sequence), then index stop -> patterns. */
  private PatternIndex buildPatterns() {
    Map<String, Map<List<String>, List<TransitIndex.Trip>>> byRoute = new HashMap<>();
    for (var t : index.trips.values()) {
      var route = index.routes.get(t.routeId());
      // Without a public number a rider cannot tell which bus to board.
      if (route == null || route.number() == null || t.visits().size() < 2) continue;
      var key = t.visits().stream().map(TransitIndex.Visit::stopId).toList();
      byRoute.computeIfAbsent(t.routeId(), k -> new HashMap<>())
          .computeIfAbsent(key, k -> new ArrayList<>()).add(t);
    }
    List<Pattern> patterns = new ArrayList<>();
    Map<String, List<Integer>> at = new HashMap<>();
    for (String routeId : new TreeSet<>(byRoute.keySet()))
      for (var e : byRoute.get(routeId).entrySet()) {
        var p = new Pattern(patterns.size(), routeId, e.getKey().toArray(String[]::new), List.copyOf(e.getValue()));
        patterns.add(p);
        for (String s : new HashSet<>(e.getKey())) at.computeIfAbsent(s, k -> new ArrayList<>()).add(p.id());
      }
    Map<String, int[]> atStop = new HashMap<>();
    at.forEach((s, ids) -> atStop.put(s, ids.stream().mapToInt(Integer::intValue).toArray()));
    Map<String, List<Pattern>> perRoute = new HashMap<>();
    for (var p : patterns) perRoute.computeIfAbsent(p.routeId(), k -> new ArrayList<>()).add(p);
    return new PatternIndex(List.copyOf(patterns), atStop, perRoute);
  }

  // ── Search ───────────────────────────────────────────────────────────────

  /**
   * Routes from the stand containing {@code fromStopId} to the stand containing {@code toStopId}.
   * With {@code walkMeters} > 0, stops within that straight-line distance of a stand's stops count
   * as well (big terminals spread over several differently named stops); each match says which
   * stop it uses and how far it is.
   */
  public RouteSearch search(String fromStopId, String toStopId, double walkMeters) {
    return search(fromStopId, toStopId, walkMeters, Instant.now());
  }

  RouteSearch search(String fromStopId, String toStopId, double walkMeters, Instant now) {
    var from = stands.standOf(fromStopId);
    var to = stands.standOf(toStopId);
    double walk = Math.max(0, Math.min(MAX_WALK_METERS, walkMeters));
    List<String> notes = new ArrayList<>(List.of(TIMETABLE_NOTE));
    boolean feed = transit.feedAvailable();
    long nowMs = now.toEpochMilli();
    if (from.id().equals(to.id()))
      return new RouteSearch(STATUS_SAME_STAND, "Pick-up and destination are the same bus stand.",
          from.view(null), to.view(null), walk, List.of(), List.of(), 0, 0, feed, live.fetchedAt, nowMs, notes);

    var plan = cache.computeIfAbsent(from.id() + ">" + to.id() + "@" + (int) walk, k -> {
      if (cache.size() >= CACHE_ENTRIES) cache.clear();
      var ends = endpoints(from, to, walk);
      var direct = matches(ends.board(), ends.alight());
      return new Plan(direct, direct.size() < TRANSFERS_WHEN_DIRECT_BELOW
          ? transfers(ends.board(), ends.alight()) : List.of());
    });

    var memo = regions.new Memo();
    int nowSod = now.atZone(StandService.SERVICE_ZONE).toLocalTime().toSecondOfDay();
    long nowSec = now.getEpochSecond();
    List<RouteMatch> routes = new ArrayList<>();
    for (var s : plan.direct()) routes.add(live(s, memo, nowSec, nowSod));
    List<Transfer> transfers = new ArrayList<>();
    for (var t : plan.transfers()) {
      var a = live(t.first(), memo, nowSec, nowSod);
      var b = live(t.second(), memo, nowSec, nowSod);
      Double total = a.scheduledMinutes() == null || b.scheduledMinutes() == null ? null
          : Math.round((a.scheduledMinutes() + b.scheduledMinutes() + walkMinutes(t.walkMeters())) * 10.0) / 10.0;
      transfers.add(new Transfer(a.key() + "~" + b.key(), a, b, t.walkMeters(), total,
          a.stops().size() - 1 + b.stops().size() - 1));
    }
    if (!transfers.isEmpty())
      notes.add("One-change journeys: the time adds both scheduled rides and the walk between stops;"
          + " waiting for the second bus is not included.");
    int withLive = (int) routes.stream().filter(r -> r.liveBusCount() > 0).count();
    if (walk > 0)
      notes.add("Stops within " + (int) walk + " m of a selected stand are included; each route"
          + " shows the stop it calls at and the straight-line walk to it.");
    notes.add("Live bus counts are buses on the live feed whose route ID maps to the route. Some"
        + " feed route IDs are out of date, so a listed bus may be running elsewhere.");

    String status = STATUS_OK, message = null;
    int reverse = 0;
    if (routes.isEmpty()) {
      if (servedPatterns(from, walk).isEmpty()) {
        status = STATUS_NO_ROUTES_AT_PICKUP;
        message = "No route in the timetable has a stop sequence calling at " + from.name() + ".";
      } else if (servedPatterns(to, walk).isEmpty()) {
        status = STATUS_NO_ROUTES_AT_DESTINATION;
        message = "No route in the timetable has a stop sequence calling at " + to.name() + ".";
      } else {
        status = STATUS_NO_ROUTES;
        reverse = matches(to, from, walk).size();
        message = "No direct bus runs from " + from.name() + " to " + to.name() + " in the timetable."
            + (reverse > 0 ? " Some routes run the other way, from " + to.name() + " to " + from.name() + "." : "");
      }
    }
    return new RouteSearch(status, message, from.view(null), to.view(null), walk, List.copyOf(routes),
        List.copyOf(transfers), withLive, reverse, feed, live.fetchedAt, nowMs, notes);
  }

  /** A static match with its current live bus count and next timetable departures. */
  private RouteMatch live(Static s, RegionService.Memo memo, long nowSec, int nowSod) {
    int liveCount = 0;
    for (String id : s.base().routeIds()) liveCount += memo.onRoute(id).size();
    return withLive(s.base(), liveCount, nextDepartures(s.departures(), nowSec, nowSod));
  }

  private static RouteMatch withLive(RouteMatch m, int liveCount, List<Departure> next) {
    return new RouteMatch(
        m.key(), m.routeNumber(), m.routeIds(), m.direction(), m.origin(), m.terminus(), m.pickup(),
        m.pickupWalkMeters(), m.pickupAtSelectedStand(), m.destination(), m.destinationWalkMeters(),
        m.destinationAtSelectedStand(), m.stops(), m.intermediateStopCount(), m.pickupStopNumber(),
        m.routeStopCount(), m.distanceKm(), m.distanceBasis(), m.scheduledMinutes(),
        m.scheduledBasis(), m.tripsPerDay(), m.firstDeparture(), m.lastDeparture(), next,
        m.variants(), liveCount);
  }

  /**
   * Stop -> straight-line walk from the stand: 0 for its own stops, else the distance to its
   * nearest member, for stops within {@code walk}.
   */
  Map<String, Double> reach(StandService.Cluster stand, double walk) {
    Map<String, Double> out = new HashMap<>();
    for (var s : stand.stops()) out.put(s.id(), 0.0);
    if (walk <= 0) return out;
    for (var s : stand.stops())
      for (var n : index.nearby(s.lat(), s.lng(), walk))
        out.merge(n.id(), Geometry.distance(s.lat(), s.lng(), n.lat(), n.lng()), Math::min);
    return out;
  }

  private Set<Integer> servedPatterns(StandService.Cluster stand, double walk) {
    Set<Integer> out = new HashSet<>();
    for (String s : reach(stand, walk).keySet())
      for (int id : patternIndex().atStop().getOrDefault(s, new int[0])) out.add(id);
    return out;
  }

  /** Every matching route, deduplicated, best first. Static data only, so cached per stand pair. */
  List<Static> matches(StandService.Cluster from, StandService.Cluster to, double walk) {
    var ends = endpoints(from, to, walk);
    return matches(ends.board(), ends.alight());
  }

  /** Pick-up and destination stops with their walks from the stands; see {@link #reach}. */
  record Endpoints(Map<String, Double> board, Map<String, Double> alight) {}

  Endpoints endpoints(StandService.Cluster from, StandService.Cluster to, double walk) {
    var board = reach(from, walk);
    var alight = reach(to, walk);
    // A stop within reach of both stands belongs to the one it is closer to (stand stops first).
    for (var it = board.entrySet().iterator(); it.hasNext(); ) {
      var e = it.next();
      Double other = alight.get(e.getKey());
      if (other == null) continue;
      if (other < e.getValue()) it.remove();
      else alight.remove(e.getKey());
    }
    return new Endpoints(board, alight);
  }

  List<Static> matches(Map<String, Double> board, Map<String, Double> alight) {
    var idx = patternIndex();

    // 1. The best ride on each pattern calling at a pick-up stop and later at a destination stop.
    Map<String, List<Ride>> byRoute = new TreeMap<>();
    Set<Integer> seen = new HashSet<>();
    for (String stop : board.keySet())
      for (int id : idx.atStop().getOrDefault(stop, new int[0])) {
        if (!seen.add(id)) continue;
        var ride = bestRide(idx.patterns().get(id), board, alight);
        if (ride != null) byRoute.computeIfAbsent(ride.pattern().routeId(), k -> new ArrayList<>()).add(ride);
      }

    // 2. Per route_id, the cheapest (pick-up stop, destination stop) pair; every pattern of the
    //    route riding between that pair adds its trips and, if its stops differ, a variant.
    record Grouped(String routeId, List<Ride> rides) {}
    List<Grouped> grouped = new ArrayList<>();
    for (var e : byRoute.entrySet()) {
      var best = e.getValue().stream()
          .min(Comparator.comparingDouble(Ride::cost)
              .thenComparing(r -> -r.pattern().trips().size())
              .thenComparingInt(r -> r.pattern().id()))
          .orElseThrow();
      String b = best.pattern().stops()[best.from()], a = best.pattern().stops()[best.to()];
      List<Ride> same = new ArrayList<>();
      for (var r : e.getValue()) {
        var ride = between(r.pattern(), b, a);
        if (ride != null) same.add(ride);
      }
      // Most-run pattern first: its stops are the ones shown.
      same.sort(Comparator.comparingInt((Ride r) -> -r.pattern().trips().size())
          .thenComparingInt(r -> r.pattern().id()));
      grouped.add(new Grouped(e.getKey(), same));
    }

    // 3. Route_ids with the same number running exactly the same stops are one choice for the rider.
    Map<String, List<Grouped>> merged = new LinkedHashMap<>();
    for (var g : grouped) {
      var r = g.rides().get(0);
      var stops = Arrays.asList(r.pattern().stops()).subList(r.from(), r.to() + 1);
      merged.computeIfAbsent(index.routes.get(g.routeId()).number() + "|" + stops, k -> new ArrayList<>()).add(g);
    }

    List<Static> out = new ArrayList<>();
    for (var group : merged.values()) {
      List<Ride> rides = new ArrayList<>();
      List<String> routeIds = new ArrayList<>();
      for (var g : group) {
        rides.addAll(g.rides());
        routeIds.add(g.routeId());
      }
      out.add(build(routeIds, rides, board, alight));
    }
    out.sort(Comparator.comparingDouble((Static s) -> cost(s.base()))
        .thenComparing(s -> s.base().routeNumber(), StandService.ROUTE_ORDER)
        .thenComparing(s -> s.base().key()));
    return List.copyOf(out);
  }

  private static double cost(RouteMatch m) {
    return m.pickupWalkMeters() + m.destinationWalkMeters() + STOP_COST_METERS * (m.stops().size() - 1);
  }

  /**
   * Cheapest pick-up index i and later destination index j on the pattern, cost = walks +
   * {@link #STOP_COST_METERS} per stop ridden; null when no destination stop follows a pick-up
   * stop. One pass: keep the cheapest boarding so far, priced as if ridden from there.
   */
  static Ride bestRide(Pattern p, Map<String, Double> board, Map<String, Double> alight) {
    int bestFrom = -1;
    double bestBoard = Double.POSITIVE_INFINITY;
    Ride best = null;
    for (int j = 0; j < p.stops().length; j++) {
      Double off = alight.get(p.stops()[j]);
      if (off != null && bestFrom >= 0) {
        double cost = bestBoard + off + STOP_COST_METERS * j;
        if (best == null || cost < best.cost()) best = new Ride(p, bestFrom, j, cost);
      }
      Double on = board.get(p.stops()[j]);
      // "<=": of equal boardings, the later one rides fewer stops.
      if (on != null && on - STOP_COST_METERS * j <= bestBoard) {
        bestBoard = on - STOP_COST_METERS * j;
        bestFrom = j;
      }
    }
    return best;
  }

  /** The shortest ride on the pattern from stop {@code b} to a later stop {@code a}; null if none. */
  static Ride between(Pattern p, String b, String a) {
    int from = -1;
    for (int j = 0; j < p.stops().length; j++) {
      if (p.stops()[j].equals(a) && from >= 0) return new Ride(p, from, j, 0);
      if (p.stops()[j].equals(b)) from = j;
    }
    return null;
  }

  // ── One change ───────────────────────────────────────────────────────────

  /** One bus ride on a pattern, from index {@code from} to {@code to}; cost in minutes, ranking only. */
  record Leg(Pattern pattern, int from, int to, double cost) {}

  /** A one-change journey before live data is added. */
  record StaticTransfer(Static first, Static second, double walkMeters, double cost) {}

  /**
   * Journeys with one change of bus: a route from a pick-up stop to a change stop, a walk of at
   * most {@link #TRANSFER_WALK_METERS} (often none), and a different route number from there to a
   * destination stop. Both legs keep their stop order, so each runs the right way. One option per
   * pair of route numbers, quickest first by the timetable plus a fixed change allowance (ranking
   * only; waiting time is not published, so it is never shown as part of the journey time).
   */
  List<StaticTransfer> transfers(Map<String, Double> board, Map<String, Double> alight) {
    var idx = patternIndex();
    // Leg 1: every stop reachable on one bus from a pick-up stop -> quickest leg per route number.
    Map<String, Map<String, Leg>> first = new HashMap<>();
    Set<Integer> seen = new HashSet<>();
    for (String s : board.keySet())
      for (int id : idx.atStop().getOrDefault(s, new int[0])) {
        if (!seen.add(id)) continue;
        var p = idx.patterns().get(id);
        int from = -1;
        double bestBoard = Double.POSITIVE_INFINITY;
        for (int j = 0; j < p.stops().length; j++) {
          String stop = p.stops()[j];
          if (from >= 0 && !board.containsKey(stop) && !alight.containsKey(stop))
            keep(first, stop, new Leg(p, from, j, legMinutes(p, from, j) + walkMinutes(board.get(p.stops()[from]))));
          Double on = board.get(stop);
          if (on != null && on - STOP_COST_METERS * j <= bestBoard) {
            bestBoard = on - STOP_COST_METERS * j;
            from = j;
          }
        }
      }
    // Leg 2: every stop from which one bus reaches a destination stop -> quickest leg per number.
    Map<String, Map<String, Leg>> second = new HashMap<>();
    seen.clear();
    for (String s : alight.keySet())
      for (int id : idx.atStop().getOrDefault(s, new int[0])) {
        if (!seen.add(id)) continue;
        var p = idx.patterns().get(id);
        int to = -1;
        double bestOff = Double.POSITIVE_INFINITY;
        for (int j = p.stops().length - 1; j >= 0; j--) {
          String stop = p.stops()[j];
          if (to >= 0 && !board.containsKey(stop) && !alight.containsKey(stop))
            keep(second, stop, new Leg(p, j, to, legMinutes(p, j, to) + walkMinutes(alight.get(p.stops()[to]))));
          Double off = alight.get(stop);
          if (off != null && off + STOP_COST_METERS * j <= bestOff) {
            bestOff = off + STOP_COST_METERS * j;
            to = j;
          }
        }
      }
    if (first.isEmpty() || second.isEmpty()) return List.of();
    Map<String, List<Leg>> secondTop = new HashMap<>();
    second.forEach((stop, legs) -> secondTop.put(stop, quickest(legs.values())));

    // Join at the same stop or across a short walk; one option per pair of route numbers.
    record Option(Leg a, Leg b, double walk, double cost) {}
    Map<String, Option> best = new HashMap<>();
    for (var e : first.entrySet()) {
      var x = index.stops.get(e.getKey());
      if (x == null) continue;
      List<Leg> legsA = null;
      for (var y : index.nearby(x.lat(), x.lng(), TRANSFER_WALK_METERS)) {
        var legsB = secondTop.get(y.id());
        if (legsB == null) continue;
        if (legsA == null) legsA = quickest(e.getValue().values()).stream().filter(l -> legMeters(l) >= MIN_LEG_METERS).toList();
        double walk = y.id().equals(x.id()) ? 0 : Geometry.distance(x.lat(), x.lng(), y.lat(), y.lng());
        for (var a : legsA)
          for (var b : legsB) {
            String na = number(a.pattern()), nb = number(b.pattern());
            if (na.equals(nb) || legMeters(b) < MIN_LEG_METERS) continue;
            double cost = a.cost() + b.cost() + walkMinutes(walk) + CHANGE_ALLOWANCE_MINUTES;
            best.merge(na + ">" + nb, new Option(a, b, walk, cost), (o, n) -> n.cost() < o.cost() ? n : o);
          }
      }
    }
    var sorted = best.values().stream()
        .sorted(Comparator.comparingDouble(Option::cost)
            .thenComparing(o -> number(o.a().pattern()), StandService.ROUTE_ORDER)
            .thenComparing(o -> number(o.b().pattern()), StandService.ROUTE_ORDER))
        .toList();
    // Varied choices: one quick first bus would otherwise fill the list with its every connection.
    List<Option> ranked = new ArrayList<>();
    Map<String, Integer> firstUses = new HashMap<>(), secondUses = new HashMap<>();
    for (var o : sorted) {
      if (ranked.size() >= MAX_TRANSFERS) break;
      String na = number(o.a().pattern()), nb = number(o.b().pattern());
      if (firstUses.getOrDefault(na, 0) >= MAX_PER_ROUTE_NUMBER || secondUses.getOrDefault(nb, 0) >= MAX_PER_ROUTE_NUMBER) continue;
      firstUses.merge(na, 1, Integer::sum);
      secondUses.merge(nb, 1, Integer::sum);
      ranked.add(o);
    }

    List<StaticTransfer> out = new ArrayList<>();
    for (var o : ranked) {
      String change = o.a().pattern().stops()[o.a().to()];
      String reboard = o.b().pattern().stops()[o.b().from()];
      var a = build(List.of(o.a().pattern().routeId()),
          ridesBetween(o.a().pattern().routeId(), o.a().pattern().stops()[o.a().from()], change),
          board, Map.of(change, 0.0));
      var b = build(List.of(o.b().pattern().routeId()),
          ridesBetween(o.b().pattern().routeId(), reboard, o.b().pattern().stops()[o.b().to()]),
          Map.of(reboard, 0.0), alight);
      out.add(new StaticTransfer(a, b, Math.round(o.walk()), o.cost()));
    }
    return List.copyOf(out);
  }

  private String number(Pattern p) {
    return index.routes.get(p.routeId()).number();
  }

  /** Keeps the cheapest leg per route number reaching {@code stop}. */
  private void keep(Map<String, Map<String, Leg>> legs, String stop, Leg leg) {
    legs.computeIfAbsent(stop, k -> new HashMap<>())
        .merge(number(leg.pattern()), leg, (a, b) -> b.cost() < a.cost() ? b : a);
  }

  private static List<Leg> quickest(Collection<Leg> legs) {
    return legs.stream().sorted(Comparator.comparingDouble(Leg::cost)).limit(LEG_OPTIONS).toList();
  }

  /** Straight line from boarding to alighting stop; rules out pointless one-stop hops. */
  private double legMeters(Leg l) {
    var a = index.stops.get(l.pattern().stops()[l.from()]);
    var b = index.stops.get(l.pattern().stops()[l.to()]);
    return a == null || b == null ? 0 : Geometry.distance(a.lat(), a.lng(), b.lat(), b.lng());
  }

  /** Timetable minutes on the pattern's first trip; ranking only, so a rough fallback is fine. */
  static double legMinutes(Pattern p, int from, int to) {
    if (!p.trips().isEmpty()) {
      var visits = p.trips().get(0).visits();
      var dep = visits.get(from);
      var arr = visits.get(to);
      int d = dep.departure() >= 0 ? dep.departure() : dep.arrival();
      int a = arr.arrival() >= 0 ? arr.arrival() : arr.departure();
      if (d >= 0 && a > d && a - d <= MAX_RIDE_SECONDS) return (a - d) / 60.0;
    }
    return (to - from) * FALLBACK_MINUTES_PER_STOP;
  }

  static double walkMinutes(Double meters) {
    return meters == null ? 0 : meters / 1000.0 / WALK_KMH * 60;
  }

  /** Every pattern of the route_id riding from stop {@code b} to a later stop {@code a}, most-run first. */
  private List<Ride> ridesBetween(String routeId, String b, String a) {
    List<Ride> out = new ArrayList<>();
    for (var p : patternIndex().byRoute().getOrDefault(routeId, List.of())) {
      var r = between(p, b, a);
      if (r != null) out.add(r);
    }
    out.sort(Comparator.comparingInt((Ride r) -> -r.pattern().trips().size()).thenComparingInt(r -> r.pattern().id()));
    return out;
  }

  private Static build(
      List<String> routeIds, List<Ride> rides, Map<String, Double> board, Map<String, Double> alight) {
    var main = rides.get(0);
    var p = main.pattern();
    var route = index.routes.get(routeIds.get(0));
    List<RouteStop> stops = new ArrayList<>();
    for (int k = main.from(); k <= main.to(); k++) stops.add(stop(p.stops()[k]));
    var pickup = stops.get(0);
    var destination = stops.get(stops.size() - 1);

    Set<List<String>> variants = new HashSet<>();
    List<Integer> rideSeconds = new ArrayList<>();
    List<Integer> departures = new ArrayList<>();
    for (var r : rides) {
      variants.add(Arrays.asList(r.pattern().stops()).subList(r.from(), r.to() + 1));
      for (var trip : r.pattern().trips()) {
        var dep = trip.visits().get(r.from());
        var arr = trip.visits().get(r.to());
        int d = dep.departure() >= 0 ? dep.departure() : dep.arrival();
        int a = arr.arrival() >= 0 ? arr.arrival() : arr.departure();
        if (d >= 0) departures.add(d);
        if (d >= 0 && a > d && a - d <= MAX_RIDE_SECONDS) rideSeconds.add(a - d);
      }
    }
    Collections.sort(rideSeconds);
    Double minutes = rideSeconds.isEmpty() ? null
        : Math.round(rideSeconds.get(rideSeconds.size() / 2) / 6.0) / 10.0;
    int[] deps = departures.stream().mapToInt(Integer::intValue).sorted().toArray();

    String basis = DISTANCE_SHAPE;
    Double meters = shapeMeters(representativeTrip(p), main.from(), main.to());
    if (meters == null) {
      basis = DISTANCE_STOP_TO_STOP;
      meters = 0.0;
      for (int k = 1; k < stops.size(); k++)
        meters += Geometry.distance(stops.get(k - 1).lat(), stops.get(k - 1).lng(), stops.get(k).lat(), stops.get(k).lng());
    }

    double pickupWalk = board.getOrDefault(pickup.id(), 0.0);
    double destWalk = alight.getOrDefault(destination.id(), 0.0);
    var base = new RouteMatch(
        String.join("+", routeIds) + "|" + pickup.id() + ">" + destination.id(),
        route.number(), List.copyOf(routeIds), route.direction(),
        stopName(p.stops()[0]), stopName(p.stops()[p.stops().length - 1]),
        pickup, Math.round(pickupWalk), pickupWalk == 0,
        destination, Math.round(destWalk), destWalk == 0,
        List.copyOf(stops), stops.size() - 2, main.from() + 1, p.stops().length,
        Math.round(meters / 100.0) / 10.0, basis,
        minutes, minutes == null ? null : TIME_SCHEDULED,
        deps.length, deps.length == 0 ? null : clock(deps[0]),
        deps.length == 0 ? null : clock(deps[deps.length - 1]), List.of(), variants.size(), 0);
    return new Static(base, deps);
  }

  private TransitIndex.Trip representativeTrip(Pattern p) {
    for (var t : p.trips()) if (t.shapeId() != null && !t.shapeId().isBlank()) return t;
    return p.trips().get(0);
  }

  private RouteStop stop(String id) {
    var s = index.stops.get(id);
    return s == null ? new RouteStop(id, id, Double.NaN, Double.NaN) : new RouteStop(s.id(), s.name().trim(), s.lat(), s.lng());
  }

  private String stopName(String id) {
    var s = index.stops.get(id);
    return s == null ? null : s.name().trim();
  }

  private static String clock(int secondOfDay) {
    return LocalTime.ofSecondOfDay(Math.floorMod(secondOfDay, 86400)).format(HH_MM);
  }

  /** The next timetable departures after now, each trip taken as running every day. */
  static List<Departure> nextDepartures(int[] departures, long nowSec, int nowSod) {
    if (departures.length == 0) return List.of();
    long[] until = new long[departures.length];
    for (int i = 0; i < departures.length; i++) until[i] = Math.floorMod(departures[i] - nowSod, 86400);
    Arrays.sort(until);
    List<Departure> out = new ArrayList<>();
    for (int i = 0; i < until.length && out.size() < NEXT_DEPARTURES; i++) {
      if (i > 0 && until[i] == until[i - 1]) continue;
      out.add(new Departure(clock((int) ((nowSod + until[i]) % 86400)), nowSec + until[i],
          Math.round(until[i] / 6.0) / 10.0));
    }
    return out;
  }

  /**
   * Distance along the trip's GTFS shape from visit {@code from} to visit {@code to}. Stops are
   * snapped to shape points in travel order, each searched for only a little beyond the previous
   * one, so a route that passes the same road twice is not short-cut. Null when the trip has no
   * shape or a stop is not near it.
   */
  Double shapeMeters(TransitIndex.Trip trip, int from, int to) {
    var shape = trip.shapeId() == null ? null : index.shapes.get(trip.shapeId());
    if (shape == null || shape.size() < 2) return null;
    double[] cum = shapeLengths.computeIfAbsent(trip.shapeId(), k -> {
      double[] c = new double[shape.size()];
      for (int i = 1; i < shape.size(); i++)
        c[i] = c[i - 1] + Geometry.distance(shape.get(i - 1).lat(), shape.get(i - 1).lng(), shape.get(i).lat(), shape.get(i).lng());
      return c;
    });
    var visits = trip.visits();
    int cursor = 0;
    double start = 0;
    TransitIndex.Stop prev = null;
    for (int k = 0; k <= to; k++) {
      var s = index.stops.get(visits.get(k).stopId());
      if (s == null) return null;
      double hop = prev == null ? 0 : Geometry.distance(prev.lat(), prev.lng(), s.lat(), s.lng());
      // Roads wind, but rarely to three times the straight line between neighbouring stops.
      double window = prev == null ? Double.POSITIVE_INFINITY : Math.max(1500, 3 * hop);
      int best = -1;
      double bestD = Double.POSITIVE_INFINITY;
      for (int i = cursor; i < shape.size() && cum[i] - cum[cursor] <= window; i++) {
        var pt = shape.get(i);
        // Cheap reject: 0.002 degrees is over 190 m in any direction here.
        if (Math.abs(pt.lat() - s.lat()) > 0.002 || Math.abs(pt.lng() - s.lng()) > 0.002) continue;
        double d = Geometry.distance(pt.lat(), pt.lng(), s.lat(), s.lng());
        if (d < bestD) {
          bestD = d;
          best = i;
        }
      }
      if (best < 0 || bestD > SHAPE_SNAP_METERS) return null;
      cursor = best;
      if (k == from) start = cum[best];
      prev = s;
    }
    double meters = cum[cursor] - start;
    return meters > 0 ? meters : null;
  }

  // ── Live buses on one matched route ──────────────────────────────────────

  /**
   * Buses on the live feed mapped to {@code routeIds}, each with where it is, its next stop and,
   * when it can be estimated, its arrival at the pick-up stop. Buses heading to the pick-up stop
   * come first, soonest first.
   */
  public RouteLive live(String pickupStopId, List<String> routeIds) {
    return live(pickupStopId, routeIds, Instant.now());
  }

  RouteLive live(String pickupStopId, List<String> routeIds, Instant now) {
    var pickup = index.stops.get(pickupStopId);
    if (pickup == null)
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Unknown bus stop ID");
    List<String> ids = routeIds.stream().map(String::trim).filter(index.routes::containsKey).distinct().toList();
    if (ids.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Unknown route ID");

    List<String> warnings = new ArrayList<>();
    var memo = regions.new Memo();
    Set<String> numbers = new HashSet<>();
    List<RealtimeSnapshot.Vehicle> vehicles = new ArrayList<>();
    for (String id : ids) {
      numbers.add(index.routes.get(id).number());
      vehicles.addAll(memo.onRoute(id));
    }
    Map<String, RegionService.Upcoming> upcoming = new HashMap<>();
    if (!vehicles.isEmpty())
      for (var u : regions.upcoming(pickupStopId, numbers, warnings, memo)) upcoming.putIfAbsent(u.vehicleId(), u);

    long nowSec = now.getEpochSecond();
    List<LiveBus> buses = new ArrayList<>();
    for (var v : vehicles) {
      var route = transit.route(v);
      var u = upcoming.get(v.id());
      String state = u != null ? PICKUP_AHEAD : pickupState(v, route, pickupStopId, memo);
      var next = regions.nextStop(v);
      String check = u != null ? u.routeCheck() : regions.routeCheck(v, route);
      buses.add(new LiveBus(
          v.id(), route.number(), route.id(), v.lat(), v.lng(), v.speedKmh(), v.timestamp(),
          Math.max(0, nowSec - v.timestamp()), locationLabel(v.lat(), v.lng()),
          next == null ? null : next.stop().id(), next == null ? null : next.stop().name(),
          next == null ? null : next.distance(), state,
          u == null || u.etaMinutes() == null ? null : Math.round(u.etaMinutes() * 100.0) / 100.0,
          u == null ? null : u.availability(), u == null ? null : u.label(),
          u == null ? null : u.distanceMeters(), u == null ? null : u.remainingStops(), check,
          u != null ? u.likelyRoutes() : regions.likelyRoutes(v, route, check)));
    }
    Map<String, Integer> order = Map.of(PICKUP_AHEAD, 0, PICKUP_UNKNOWN, 1, PICKUP_PASSED, 2);
    buses.sort(Comparator.comparingInt((LiveBus b) -> order.get(b.pickupState()))
        .thenComparing(LiveBus::etaMinutes, Comparator.nullsLast(Double::compareTo))
        .thenComparing(LiveBus::vehicleId));
    int ahead = (int) buses.stream().filter(b -> PICKUP_AHEAD.equals(b.pickupState())).count();

    boolean feed = transit.feedAvailable();
    if (!feed && live.feedError != null) warnings.add(live.feedError);
    String message = !feed ? "The live bus feed is unavailable right now."
        : buses.isEmpty() ? "No live buses currently available on this route." : null;
    return new RouteLive(pickup.id(), pickup.name().trim(), ids, List.copyOf(buses), ahead, feed,
        live.fetchedAt, now.toEpochMilli(), message, warnings);
  }

  /** Whether the bus has already passed the pick-up stop, judged on its learned or timetable path. */
  private String pickupState(
      RealtimeSnapshot.Vehicle v, TransitIndex.Route route, String stopId, RegionService.Memo memo) {
    var on = memo.onLearnedPath(v, route);
    if (on != null) {
      var target = on.route().stop(stopId);
      if (target != null)
        return target.along() < on.at().along() - RegionService.PASSED_TOLERANCE_METERS ? PICKUP_PASSED : PICKUP_AHEAD;
    }
    var progress = memo.locateVehicle(v, route);
    if (progress != null) {
      int at = progress.indexOf(stopId);
      if (at >= 0)
        return progress.cumulative()[at] < progress.along() - RegionService.PASSED_TOLERANCE_METERS
            ? PICKUP_PASSED : PICKUP_AHEAD;
    }
    return PICKUP_UNKNOWN;
  }

  private String locationLabel(double lat, double lng) {
    var near = index.nearby(lat, lng, StandService.LOCATION_LABEL_METERS);
    return near.isEmpty() ? null : "Near " + near.get(0).name().trim();
  }
}
