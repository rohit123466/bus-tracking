package com.dtc.bus_tracker.live;

import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Direct and one-transfer bus journeys over the static GTFS stop patterns in {@link TransitIndex}.
 *
 * <p>The legacy {@code /api/journey/plan} reads the relational database, which in production holds
 * only the ticketing demo stops. This planner uses the same in-memory GTFS index as the rest of the
 * live API, so stops and IDs match the map. Every number it returns is derived from that data and is
 * labelled with its basis: scheduled ride times come from stop_times, walking is straight-line at
 * {@link #WALK_KMH}, and waiting time is never included because the feed has no reliable current
 * frequencies. Nothing here invents a stop, a connection or a route path.
 */
@Service
public class JourneyService {
  static final double WALK_KMH = 5.0;
  /** Only used when a pattern has no usable scheduled times; labelled ASSUMED_SPEED. */
  static final double ASSUMED_BUS_KMH = RegionService.TYPICAL_BUS_SPEED_KMH;
  static final double ACCESS_RADIUS_METERS = 800;
  static final double WIDE_ACCESS_RADIUS_METERS = 1500;
  static final int MAX_ACCESS_STOPS = 12;
  /** Two stops this close are treated as one interchange (opposite sides of a road, same stand). */
  static final double TRANSFER_RADIUS_METERS = 150;
  static final int MAX_STOPS_TO_TRANSFER = 60;
  static final double MIN_TRANSFER_LEG_METERS = 800;
  static final int MAX_OPTIONS = 5;

  public static final String TIMETABLE_NOTE =
      "Based on the static DTC GTFS timetable bundled with the server (June 2024). Routes, stops"
          + " and times may have changed since.";

  public enum Preference {
    FASTEST,
    FEWEST_TRANSFERS,
    MINIMUM_WALKING;

    static Preference parse(String raw) {
      try {
        return valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return FASTEST;
      }
    }
  }

  /** WALK or BUS. Bus-only fields are null on walking segments. */
  public record Segment(
      String type,
      TransitIndex.Stop from,
      TransitIndex.Stop to,
      double meters,
      double minutes,
      /** STRAIGHT_LINE (walk), SCHEDULED_GTFS or ASSUMED_SPEED (bus). */
      String basis,
      String routeNumber,
      String routeId,
      String direction,
      /** Terminus of the GTFS pattern this leg runs on, e.g. what a bus would show as destination. */
      String towards,
      Integer stopCount,
      /** Ordered stops from boarding to alighting, inclusive. Straight lines between them only. */
      List<TransitIndex.Stop> stops) {}

  public record Journey(
      boolean direct,
      int transfers,
      double totalMinutes,
      double walkMinutes,
      double rideMinutes,
      double walkMeters,
      List<Segment> segments) {}

  public record Plan(
      String status, String message, List<Journey> options, List<String> notes) {}

  public record Pattern(
      String routeId,
      String routeNumber,
      String direction,
      String towards,
      List<TransitIndex.Stop> stops) {}

  /** One representative trip per GTFS route_id: the one visiting the most stops. */
  private record Shape(String routeId, List<TransitIndex.Visit> visits, Map<String, Integer> at) {}

  private final TransitIndex index;
  private volatile Map<String, Shape> shapes;

  public JourneyService(TransitIndex index) {
    this.index = index;
  }

  /** Ordered stop patterns (one per GTFS route_id) for a public route number. */
  public List<Pattern> patterns(String routeNumber) {
    List<Pattern> out = new ArrayList<>();
    for (var route : index.routes.values()) {
      if (route.number() == null || !route.number().equalsIgnoreCase(routeNumber.trim())) continue;
      Shape shape = shapes().get(route.id());
      if (shape == null) continue;
      List<TransitIndex.Stop> stops = stopsOf(shape, 0, shape.visits().size() - 1);
      if (stops.size() < 2) continue;
      out.add(
          new Pattern(
              route.id(),
              route.number(),
              route.direction(),
              stops.get(stops.size() - 1).name(),
              stops));
    }
    out.sort(Comparator.comparing(Pattern::direction).thenComparing(Pattern::routeId));
    return out;
  }

  public Plan plan(double srcLat, double srcLng, double dstLat, double dstLng, String preference) {
    var pref = Preference.parse(preference);
    var notes = new ArrayList<String>();
    notes.add(TIMETABLE_NOTE);
    notes.add("Walking distances are straight-line estimates at 5 km/h, not street routes.");
    notes.add("Total time excludes waiting at stops: current bus frequencies are not published.");

    var origin = access(srcLat, srcLng);
    var target = access(dstLat, dstLng);
    if (Geometry.distance(srcLat, srcLng, dstLat, dstLng) < 300)
      return new Plan("TOO_CLOSE", "Start and destination are within 300 m: walking is quicker.",
          List.of(), notes);
    if (origin.isEmpty())
      return new Plan("NO_STOP_NEAR_START",
          "No bus stop in the timetable within 1.5 km of the start.", List.of(), notes);
    if (target.isEmpty())
      return new Plan("NO_STOP_NEAR_DESTINATION",
          "No bus stop in the timetable within 1.5 km of the destination.", List.of(), notes);

    Map<String, Double> walkOut = new HashMap<>();
    for (var s : target) walkOut.put(s.id(), Geometry.distance(dstLat, dstLng, s.lat(), s.lng()));

    List<Journey> found = new ArrayList<>();
    for (var board : origin) {
      double walkIn = Geometry.distance(srcLat, srcLng, board.lat(), board.lng());
      for (Shape a : shapesAt(board.id())) {
        int i = a.at().get(board.id());
        // Direct: an alighting candidate later on the same pattern.
        for (var e : walkOut.entrySet()) {
          Integer j = a.at().get(e.getKey());
          if (j == null || j <= i) continue;
          var leg = bus(a, i, j);
          found.add(journey(List.of(
              walk(point("Start", srcLat, srcLng), board, walkIn), leg,
              walk(index.stops.get(e.getKey()), point("Destination", dstLat, dstLng), e.getValue()))));
        }
      }
    }
    if (distinctRoutes(found) < 3) found.addAll(transfers(origin, walkOut, srcLat, srcLng, dstLat, dstLng));

    Comparator<Journey> order =
        switch (pref) {
          case FEWEST_TRANSFERS -> Comparator.comparingInt(Journey::transfers)
              .thenComparingDouble(Journey::totalMinutes);
          case MINIMUM_WALKING -> Comparator.comparingDouble(Journey::walkMeters)
              .thenComparingDouble(Journey::totalMinutes);
          default -> Comparator.comparingDouble(Journey::totalMinutes);
        };
    found.sort(order);
    // One option per sequence of route numbers keeps the list varied instead of five stop pairs
    // on the same bus.
    Map<String, Journey> best = new LinkedHashMap<>();
    for (var j : found) best.putIfAbsent(routeKey(j), j);
    var options = best.values().stream().limit(MAX_OPTIONS).toList();
    if (options.isEmpty())
      return new Plan("NO_CONNECTION",
          "No direct or one-change bus connection between stops near these places in the timetable.",
          options, notes);
    return new Plan("OK", null, options, notes);
  }

  private List<Journey> transfers(
      List<TransitIndex.Stop> origin, Map<String, Double> walkOut,
      double srcLat, double srcLng, double dstLat, double dstLng) {
    List<Journey> out = new ArrayList<>();
    Map<String, List<TransitIndex.Stop>> near = new HashMap<>();
    for (var board : origin) {
      double walkIn = Geometry.distance(srcLat, srcLng, board.lat(), board.lng());
      for (Shape a : shapesAt(board.id())) {
        int i = a.at().get(board.id());
        int last = Math.min(a.visits().size() - 1, i + MAX_STOPS_TO_TRANSFER);
        for (int k = i + 1; k <= last; k++) {
          var change = index.stops.get(a.visits().get(k).stopId());
          if (change == null) continue;
          var interchange = near.computeIfAbsent(change.id(),
              id -> index.nearby(change.lat(), change.lng(), TRANSFER_RADIUS_METERS));
          for (var change2 : interchange) {
            for (Shape b : shapesAt(change2.id())) {
              if (sameRouteNumber(a, b)) continue;
              int m = b.at().get(change2.id());
              for (var e : walkOut.entrySet()) {
                Integer n = b.at().get(e.getKey());
                if (n == null || n <= m) continue;
                var first = bus(a, i, k);
                var second = bus(b, m, n);
                // A change of bus for a leg this short is slower than walking it.
                if (first.meters() < MIN_TRANSFER_LEG_METERS || second.meters() < MIN_TRANSFER_LEG_METERS)
                  continue;
                List<Segment> segs = new ArrayList<>();
                segs.add(walk(point("Start", srcLat, srcLng), board, walkIn));
                segs.add(first);
                double between = Geometry.distance(change.lat(), change.lng(), change2.lat(), change2.lng());
                if (!change.id().equals(change2.id())) segs.add(walk(change, change2, between));
                segs.add(second);
                segs.add(walk(index.stops.get(e.getKey()), point("Destination", dstLat, dstLng), e.getValue()));
                out.add(journey(segs));
                if (out.size() >= 2000) return out; // plenty to rank from; bounds request time
              }
            }
          }
        }
      }
    }
    return out;
  }

  private List<TransitIndex.Stop> access(double lat, double lng) {
    var stops = index.nearby(lat, lng, ACCESS_RADIUS_METERS);
    if (stops.isEmpty()) stops = index.nearby(lat, lng, WIDE_ACCESS_RADIUS_METERS);
    return stops.stream().filter(s -> !shapesAt(s.id()).isEmpty()).limit(MAX_ACCESS_STOPS).toList();
  }

  private List<Shape> shapesAt(String stopId) {
    List<Shape> out = new ArrayList<>();
    for (String routeId : index.routesAtStop.getOrDefault(stopId, Set.of())) {
      Shape s = shapes().get(routeId);
      if (s != null && s.at().containsKey(stopId)) out.add(s);
    }
    return out;
  }

  private Segment bus(Shape shape, int from, int to) {
    var route = index.routes.get(shape.routeId());
    var stops = stopsOf(shape, from, to);
    double meters = 0;
    for (int x = 1; x < stops.size(); x++)
      meters += Geometry.distance(stops.get(x - 1).lat(), stops.get(x - 1).lng(),
          stops.get(x).lat(), stops.get(x).lng());
    var dep = shape.visits().get(from).departure();
    var arr = shape.visits().get(to).arrival();
    boolean scheduled = dep >= 0 && arr > dep && arr - dep <= 4 * 3600;
    double minutes = scheduled ? (arr - dep) / 60.0 : meters / 1000.0 / ASSUMED_BUS_KMH * 60;
    var terminus = index.stops.get(shape.visits().get(shape.visits().size() - 1).stopId());
    return new Segment(
        "BUS", stops.get(0), stops.get(stops.size() - 1), round(meters), round(minutes),
        scheduled ? "SCHEDULED_GTFS" : "ASSUMED_SPEED",
        route.number(), route.id(), route.direction(),
        terminus == null ? null : terminus.name(), to - from, stops);
  }

  private static Segment walk(TransitIndex.Stop from, TransitIndex.Stop to, double meters) {
    return new Segment("WALK", from, to, round(meters), round(meters / 1000.0 / WALK_KMH * 60),
        "STRAIGHT_LINE", null, null, null, null, null, null);
  }

  private static Journey journey(List<Segment> segments) {
    double walkMin = 0, rideMin = 0, walkM = 0;
    int buses = 0;
    for (var s : segments) {
      if (s.type().equals("BUS")) {
        rideMin += s.minutes();
        buses++;
      } else {
        walkMin += s.minutes();
        walkM += s.meters();
      }
    }
    return new Journey(buses == 1, buses - 1, round(walkMin + rideMin), round(walkMin),
        round(rideMin), round(walkM), segments);
  }

  private List<TransitIndex.Stop> stopsOf(Shape shape, int from, int to) {
    List<TransitIndex.Stop> out = new ArrayList<>();
    for (int x = from; x <= to; x++) {
      var s = index.stops.get(shape.visits().get(x).stopId());
      if (s != null) out.add(s);
    }
    return out;
  }

  private boolean sameRouteNumber(Shape a, Shape b) {
    var ra = index.routes.get(a.routeId());
    var rb = index.routes.get(b.routeId());
    return ra != null && rb != null && Objects.equals(ra.number(), rb.number());
  }

  private static String routeKey(Journey j) {
    StringBuilder key = new StringBuilder();
    for (var s : j.segments()) if (s.type().equals("BUS")) key.append(s.routeNumber()).append('>');
    return key.toString();
  }

  private static int distinctRoutes(List<Journey> journeys) {
    Set<String> keys = new HashSet<>();
    for (var j : journeys) keys.add(routeKey(j));
    return keys.size();
  }

  private static TransitIndex.Stop point(String name, double lat, double lng) {
    return new TransitIndex.Stop(null, name, lat, lng);
  }

  private static double round(double v) {
    return Math.round(v * 10.0) / 10.0;
  }

  /** Built once on first use: one pass over trips, keeping the longest trip per route_id. */
  private Map<String, Shape> shapes() {
    var local = shapes;
    if (local != null) return local;
    synchronized (this) {
      if (shapes != null) return shapes;
      Map<String, TransitIndex.Trip> longest = new HashMap<>();
      for (var t : index.trips.values()) {
        var route = index.routes.get(t.routeId());
        // Without a public number a rider cannot tell which bus to board.
        if (t.visits().size() < 2 || route == null || route.number() == null) continue;
        longest.merge(t.routeId(), t, (x, y) -> y.visits().size() > x.visits().size() ? y : x);
      }
      Map<String, Shape> built = new HashMap<>();
      for (var t : longest.values()) {
        Map<String, Integer> at = new HashMap<>();
        var visits = t.visits();
        // First occurrence wins, so loops board at the earliest visit.
        for (int x = 0; x < visits.size(); x++) at.putIfAbsent(visits.get(x).stopId(), x);
        built.put(t.routeId(), new Shape(t.routeId(), visits, at));
      }
      shapes = built;
      return built;
    }
  }
}
