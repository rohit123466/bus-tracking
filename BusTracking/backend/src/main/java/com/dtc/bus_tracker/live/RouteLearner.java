package com.dtc.bus_tracker.live;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Learns where each route really runs from the live GPS feed, because the bundled static GTFS
 * (June 2024) no longer matches most live route IDs: buses are typically kilometres away from the
 * stops their route lists.
 *
 * <p>Every poll, each moving vehicle's positions are appended to a recording for its route and
 * trip. A recording ends when the trip changes, the bus goes silent, or its GPS jumps. The longest
 * recording seen for a route becomes that route's learned path: the road it actually drives, in
 * order, with the time the bus took to get from point to point. Stops lying on that path are the
 * stops the route really serves, and the recorded times give arrival estimates.
 *
 * <p>Only the learned paths are kept (and saved to a small file so restarts keep them); raw GPS
 * history is discarded as soon as a recording ends.
 */
@Service
public class RouteLearner {
  /** Movement shorter than this between polls is GPS jitter or a stationary bus. */
  static final double MIN_STEP_METERS = 25;
  /** A longer jump between two polls is a GPS glitch or a data gap, not driving. */
  static final double MAX_STEP_METERS = 2500;
  static final double MAX_SPEED_KMH = 90;
  /** No movement for this long ends a recording (end of service, long layover, feed outage). */
  static final long MAX_SILENCE_SECONDS = 900;
  /** Safety cap on one recording; a 2-hour trip at one point per 10 s is ~720 points. */
  static final int MAX_POINTS = 2000;
  /** A stop counts as on the route when it is this close to the driven path. */
  static final double STOP_ON_PATH_METERS = 40;
  /** A bus counts as on its learned path when its GPS is this close to it. */
  static final double MAX_BUS_OFFSET_METERS = 150;
  /** A replacement path must be at least this much longer, so stops are not re-matched every poll. */
  static final double MIN_GROWTH_METERS = 500;
  /** An older path is refreshed by a newer, nearly complete one so its travel times stay current. */
  static final long REFRESH_AFTER_SECONDS = 6 * 3600;

  public record TracePoint(double lat, double lng, long time) {}

  public record LearnedStop(String stopId, double along, double seconds) {}

  public record Located(double along, double offset, double bearing) {}

  /** A route's driven path, with cumulative distance and elapsed time at every point. */
  public static final class LearnedRoute {
    final String routeId;
    final long learnedAt;
    final List<TracePoint> points;
    final double[] cumulative;
    final double[] seconds;
    final List<LearnedStop> stops;
    private final Map<String, LearnedStop> stopById = new HashMap<>();
    private final List<TransitIndex.Point> geometry;

    LearnedRoute(String routeId, long learnedAt, List<TracePoint> points, TransitIndex index) {
      this.routeId = routeId;
      this.learnedAt = learnedAt;
      this.points = List.copyOf(points);
      cumulative = new double[points.size()];
      seconds = new double[points.size()];
      List<TransitIndex.Point> geo = new ArrayList<>(points.size());
      for (int i = 0; i < points.size(); i++) {
        var p = points.get(i);
        geo.add(new TransitIndex.Point(p.lat(), p.lng(), i));
        if (i > 0) {
          var q = points.get(i - 1);
          cumulative[i] = cumulative[i - 1] + Geometry.distance(q.lat(), q.lng(), p.lat(), p.lng());
          seconds[i] = p.time() - points.get(0).time();
        }
      }
      geometry = geo;
      stops = matchStops(index);
      for (var s : stops) stopById.putIfAbsent(s.stopId(), s);
    }

    public String routeId() { return routeId; }
    public long learnedAt() { return learnedAt; }
    public double lengthMeters() { return cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1]; }
    public List<LearnedStop> stops() { return stops; }
    public LearnedStop stop(String stopId) { return stopById.get(stopId); }

    /** Stops within {@link #STOP_ON_PATH_METERS} of the path, in driving order. */
    private List<LearnedStop> matchStops(TransitIndex index) {
      Map<String, LearnedStop> found = new HashMap<>();
      for (int i = 0; i < points.size(); i++) {
        var p = points.get(i);
        double reach = STOP_ON_PATH_METERS
            + (i + 1 < points.size() ? (cumulative[i + 1] - cumulative[i]) / 2 + 1 : 0);
        for (var s : index.nearby(p.lat(), p.lng(), reach)) {
          if (found.containsKey(s.id())) continue;
          var at = locate(s.lat(), s.lng());
          if (at.offset() <= STOP_ON_PATH_METERS)
            found.put(s.id(), new LearnedStop(s.id(), at.along(), secondsAt(at.along())));
        }
      }
      List<LearnedStop> ordered = new ArrayList<>(found.values());
      ordered.sort(Comparator.comparingDouble(LearnedStop::along));
      return List.copyOf(ordered);
    }

    public Located locate(double lat, double lng) {
      if (geometry.size() < 2) return new Located(0, Double.POSITIVE_INFINITY, 0);
      var p = Geometry.project(geometry, lat, lng);
      return new Located(p.along(), p.offset(), p.bearing());
    }

    /** Seconds the recorded bus took to get from the start of the path to {@code along}. */
    public double secondsAt(double along) {
      if (cumulative.length == 0) return 0;
      int i = Arrays.binarySearch(cumulative, along);
      if (i >= 0) return seconds[i];
      int hi = -i - 1;
      if (hi <= 0) return seconds[0];
      if (hi >= cumulative.length) return seconds[seconds.length - 1];
      double span = cumulative[hi] - cumulative[hi - 1];
      double f = span == 0 ? 0 : (along - cumulative[hi - 1]) / span;
      return seconds[hi - 1] + f * (seconds[hi] - seconds[hi - 1]);
    }

    /** Learned stops strictly between two positions along the path. */
    public int stopsBetween(double fromAlong, double toAlong) {
      int n = 0;
      for (var s : stops) if (s.along() > fromAlong && s.along() < toAlong) n++;
      return n;
    }
  }

  /** One vehicle's in-progress drive along one trip. */
  static final class Recording {
    final String routeId;
    final String tripId;
    final List<TracePoint> points = new ArrayList<>();
    double length;
    long lastMovedAt;
    long lastSeen;
    /** Direction of the most recent movement, degrees clockwise from north; null before any. */
    Double heading;

    Recording(String routeId, String tripId) {
      this.routeId = routeId;
      this.tripId = tripId;
    }
  }

  public record Status(
      int learnedRoutes,
      int routesRunningNow,
      int learnedOfRunning,
      int vehiclesRecording,
      long startedAt,
      String note) {}

  private final TransitIndex index;
  private final LiveTransitService transit;
  private final Map<String, Recording> recordings = new HashMap<>();
  private final Map<String, LearnedRoute> learned = new ConcurrentHashMap<>();
  private final long startedAt = Instant.now().getEpochSecond();
  private volatile boolean dirty;

  @Value("${transit.learned-routes.file:./data/learned-routes.txt}")
  String file = "./data/learned-routes.txt";

  public RouteLearner(TransitIndex index, LiveTransitService transit) {
    this.index = index;
    this.transit = transit;
  }

  public LearnedRoute route(String routeId) {
    return routeId == null ? null : learned.get(routeId);
  }

  public Collection<LearnedRoute> routes() {
    return learned.values();
  }

  /** Direction the vehicle last moved in, or null when it has not moved recently. */
  public synchronized Double heading(String vehicleId) {
    var r = recordings.get(vehicleId);
    return r == null || r.heading == null || r.lastSeen - r.lastMovedAt > 120 ? null : r.heading;
  }

  @Scheduled(fixedDelayString = "${transit.learning.interval:10000}", initialDelay = 15000)
  public void poll() {
    observe(
        transit.liveVehicles().stream().filter(v -> RealtimeSnapshot.fresh(v.timestamp())).toList(),
        Instant.now().getEpochSecond());
  }

  /** Feeds one snapshot of (fresh) vehicle positions into the recordings. */
  synchronized void observe(Collection<RealtimeSnapshot.Vehicle> vehicles, long now) {
    Set<String> seen = new HashSet<>();
    for (var v : vehicles) {
      var route = transit.route(v);
      if (route == null || route.number() == null) continue; // cannot be shown to passengers
      seen.add(v.id());
      String trip = v.tripId() == null ? "" : v.tripId();
      var rec = recordings.get(v.id());
      if (rec != null && (!rec.routeId.equals(route.id()) || !rec.tripId.equals(trip))) {
        finish(rec);
        rec = null;
      }
      if (rec == null) {
        rec = new Recording(route.id(), trip);
        recordings.put(v.id(), rec);
      }
      add(rec, new TracePoint(v.lat(), v.lng(), v.timestamp()));
    }
    // Vehicles that left the feed or went silent too long end their recordings.
    for (var it = recordings.entrySet().iterator(); it.hasNext(); ) {
      var e = it.next();
      var rec = e.getValue();
      if (!seen.contains(e.getKey()) && now - rec.lastSeen > MAX_SILENCE_SECONDS) {
        finish(rec);
        it.remove();
      }
    }
  }

  private void add(Recording rec, TracePoint p) {
    if (rec.lastSeen >= p.time()) return; // same or older report
    if (rec.points.isEmpty()) {
      rec.points.add(p);
      rec.lastMovedAt = rec.lastSeen = p.time();
      return;
    }
    var last = rec.points.get(rec.points.size() - 1);
    double step = Geometry.distance(last.lat(), last.lng(), p.lat(), p.lng());
    long dt = p.time() - last.time();
    boolean silentTooLong = p.time() - rec.lastMovedAt > MAX_SILENCE_SECONDS;
    boolean glitch = step > MAX_STEP_METERS || (dt > 0 && step / dt * 3.6 > MAX_SPEED_KMH);
    if (silentTooLong || glitch) {
      finish(rec);
      rec.points.clear();
      rec.length = 0;
      rec.heading = null;
      rec.points.add(p);
      rec.lastMovedAt = rec.lastSeen = p.time();
      return;
    }
    rec.lastSeen = p.time();
    if (step < MIN_STEP_METERS) {
      // Still waiting at the start (terminal layover): move the start time forward so the wait is
      // not counted as travel time.
      if (rec.points.size() == 1) rec.points.set(0, p);
      return;
    }
    rec.heading = bearing(last, p);
    rec.points.add(p);
    rec.length += step;
    rec.lastMovedAt = p.time();
    if (rec.points.size() >= MAX_POINTS) {
      finish(rec);
      rec.points.subList(0, rec.points.size() - 1).clear();
      rec.length = 0;
      return;
    }
    // Promote early while it grows, so a route is usable before the first trip ends.
    var current = learned.get(rec.routeId);
    if (current == null ? rec.length >= 2 * MIN_GROWTH_METERS
        : rec.length >= current.lengthMeters() + MIN_GROWTH_METERS) promote(rec);
  }

  /** At the end of a recording: keep it if it is longer, or a fresher near-complete drive. */
  private void finish(Recording rec) {
    if (rec.points.size() < 2) return;
    var current = learned.get(rec.routeId);
    long age = current == null ? Long.MAX_VALUE : rec.lastSeen - current.learnedAt();
    if (current == null
        || rec.length > current.lengthMeters()
        || (age > REFRESH_AFTER_SECONDS && rec.length >= 0.9 * current.lengthMeters())) promote(rec);
  }

  private void promote(Recording rec) {
    learned.put(rec.routeId, new LearnedRoute(rec.routeId, rec.lastSeen, rec.points, index));
    dirty = true;
  }

  static double bearing(TracePoint a, TracePoint b) {
    double scale = Math.cos(Math.toRadians(a.lat()));
    double x = (b.lng() - a.lng()) * scale, y = b.lat() - a.lat();
    return (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
  }

  public Status status() {
    Set<String> running = new HashSet<>();
    for (var v : transit.liveVehicles()) {
      if (!RealtimeSnapshot.fresh(v.timestamp())) continue;
      var r = transit.route(v);
      if (r != null && r.number() != null) running.add(r.id());
    }
    int learnedRunning = (int) running.stream().filter(learned::containsKey).count();
    int recordingNow;
    synchronized (this) {
      recordingNow = recordings.size();
    }
    return new Status(
        learned.size(), running.size(), learnedRunning, recordingNow, startedAt,
        "A route's real path is learned by watching its buses drive; the longest drive seen is"
            + " kept, so coverage grows over the first hours of service.");
  }

  // ── Persistence: one line per route, "routeId|learnedAt|lat,lng,time;lat,lng,time;..." ──

  @PostConstruct
  public void load() {
    Path path = Path.of(file);
    if (!Files.exists(path)) return;
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) continue;
        String[] parts = line.split("\\|", 3);
        if (parts.length != 3 || parts[2].isEmpty()) continue;
        List<TracePoint> points = new ArrayList<>();
        for (String p : parts[2].split(";")) {
          String[] f = p.split(",");
          points.add(new TracePoint(
              Double.parseDouble(f[0]), Double.parseDouble(f[1]), Long.parseLong(f[2])));
        }
        if (points.size() >= 2)
          learned.put(parts[0], new LearnedRoute(parts[0], Long.parseLong(parts[1]), points, index));
      }
      System.out.println("[RouteLearner] Loaded " + learned.size() + " learned route paths from " + path);
    } catch (Exception e) {
      System.err.println("[RouteLearner] Could not read " + path + ": " + e.getMessage());
    }
  }

  @Scheduled(fixedDelayString = "${transit.learned-routes.save-interval:300000}", initialDelay = 300000)
  public void saveIfChanged() {
    if (dirty) save();
  }

  @PreDestroy
  public synchronized void save() {
    Path path = Path.of(file);
    StringBuilder out = new StringBuilder("# Route paths learned from live GPS by RouteLearner\n");
    for (var r : learned.values()) {
      out.append(r.routeId()).append('|').append(r.learnedAt()).append('|');
      for (int i = 0; i < r.points.size(); i++) {
        var p = r.points.get(i);
        if (i > 0) out.append(';');
        out.append(String.format(Locale.ROOT, "%.6f,%.6f,%d", p.lat(), p.lng(), p.time()));
      }
      out.append('\n');
    }
    try {
      if (path.getParent() != null) Files.createDirectories(path.getParent());
      Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
      Files.writeString(tmp, out, StandardCharsets.UTF_8);
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      dirty = false;
    } catch (IOException e) {
      System.err.println("[RouteLearner] Could not save " + path + ": " + e.getMessage());
    }
  }
}
