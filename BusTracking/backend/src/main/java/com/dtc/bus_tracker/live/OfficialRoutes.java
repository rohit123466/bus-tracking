package com.dtc.bus_tracker.live;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * DTC's current official route list (stop order per direction), with each stop placed at the
 * GTFS stop its name was matched to by {@link DtcRouteImport}. Unlike the June-2024 timetable,
 * this says where each route number runs today.
 *
 * <p>Two uses: checking that a bus is on the official path of the number shown for it, and
 * recognising which official route a driven path (from {@link RouteLearner}) belongs to, for the
 * many live route IDs whose 2024 number no longer matches where their buses drive.
 */
@Component
public class OfficialRoutes {
  /** One listed route direction as a line through its matched stops. */
  public record Path(
      int id, String sheet, String number, String direction, List<TransitIndex.Point> points,
      double[] cumulative) {
    public double lengthMeters() {
      return cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1];
    }
  }

  /**
   * How well a driven path fits one official path: the share of the drive on the path, the share
   * of the path's length the drive spans, and the share of stops in that span the drive passed.
   */
  public record Fit(
      String number, String direction, int pathId, double trackCoverage, double spanCoverage,
      double stopCoverage, double score) {}

  /** A bus within this of an official path is taken to be on it (stop-to-stop lines cut corners). */
  public static final double ON_PATH_METERS = 500;
  /** A driven path point counts as following an official path within this distance. */
  static final double FOLLOW_METERS = 250;
  static final double SAMPLE_METERS = 100;
  /** Shorter drives fit too many routes to tell them apart. */
  static final double MIN_TRACK_METERS = 3000;
  /** Share of a drive that must lie on an official path for the route to remain possible. */
  static final double MIN_CONSISTENT_COVERAGE = 0.9;

  private static final double CELL_DEGREES = 0.005; // ~550 m by ~490 m around Delhi

  private record Segment(Path path, int index) {}

  private final Map<String, List<Path>> byNumber = new HashMap<>();
  private final List<Path> paths = new ArrayList<>();
  private final Map<Long, List<Segment>> grid = new HashMap<>();
  private String source = "";

  @Value("${transit.official-routes-resource:classpath:static/dtc-official-routes.csv.gz}")
  private Resource resource;

  @PostConstruct
  public void load() {
    if (resource == null || !resource.exists()) {
      System.out.println("[OfficialRoutes] No official route list found; checks use the GTFS timetable only.");
      return;
    }
    try (var in = resource.getInputStream()) {
      read(resource.getFilename() != null && resource.getFilename().endsWith(".gz")
          ? new GZIPInputStream(in) : in);
      System.out.println("[OfficialRoutes] Loaded " + paths.size() + " official route paths for "
          + byNumber.size() + " route numbers (" + source + ")");
    } catch (IOException | RuntimeException e) {
      System.err.println("[OfficialRoutes] Could not read the official route list: " + e.getMessage());
    }
  }

  /** Reads {@link DtcRouteImport}'s CSV; rows without a matched stop are skipped. */
  public void read(InputStream csv) throws IOException {
    var reader = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8));
    Map<Integer, String[]> meta = new LinkedHashMap<>();
    Map<Integer, List<TransitIndex.Point>> points = new HashMap<>();
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("#")) {
        source = line.substring(1).trim();
        continue;
      }
      if (line.startsWith("path,") || line.isBlank()) continue;
      var f = DtcRouteImport.parseCsvLine(line);
      if (f.size() < 11 || f.get(8).isEmpty()) continue;
      int id = Integer.parseInt(f.get(0));
      meta.putIfAbsent(id, new String[] {f.get(1), f.get(2), f.get(3)});
      points.computeIfAbsent(id, k -> new ArrayList<>())
          .add(new TransitIndex.Point(
              Double.parseDouble(f.get(8)), Double.parseDouble(f.get(9)), Integer.parseInt(f.get(4))));
    }
    meta.forEach((id, m) -> {
      var pts = points.get(id);
      if (pts != null && pts.size() >= 2) add(new Path(id, m[0], display(m[1]), m[2], List.copyOf(pts), cumulative(pts)));
    });
  }

  /** Adds one route direction through the given stop positions. */
  public Path add(String number, String direction, List<TransitIndex.Point> points) {
    var p = new Path(paths.size() + 1, "", display(number), direction, List.copyOf(points), cumulative(points));
    add(p);
    return p;
  }

  private void add(Path p) {
    paths.add(p);
    byNumber.computeIfAbsent(key(p.number()), k -> new ArrayList<>()).add(p);
    for (int i = 1; i < p.points().size(); i++) {
      var a = p.points().get(i - 1);
      var b = p.points().get(i);
      // Register the segment in every cell its bounding box touches.
      for (long y = cell(Math.min(a.lat(), b.lat())); y <= cell(Math.max(a.lat(), b.lat())); y++)
        for (long x = cell(Math.min(a.lng(), b.lng())); x <= cell(Math.max(a.lng(), b.lng())); x++)
          grid.computeIfAbsent(y * 100_000 + x, k -> new ArrayList<>()).add(new Segment(p, i));
    }
  }

  private static double[] cumulative(List<TransitIndex.Point> pts) {
    double[] c = new double[pts.size()];
    for (int i = 1; i < pts.size(); i++)
      c[i] = c[i - 1] + Geometry.distance(pts.get(i - 1).lat(), pts.get(i - 1).lng(), pts.get(i).lat(), pts.get(i).lng());
    return c;
  }

  private static long cell(double degrees) {
    return (long) Math.floor(degrees / CELL_DEGREES);
  }

  /**
   * Comparable form of a route number: "Route 03" = "3", "39 STL" = "39STL", "D-7101" = "D7101".
   * A night-service copy ("740 (NS)") is the same route number.
   */
  public static String key(String number) {
    if (number == null) return "";
    String k = number.toUpperCase(Locale.ROOT).replaceAll("\\(\\s*NS\\s*\\)", "");
    k = k.replaceAll("[^A-Z0-9+]", "");
    k = k.replaceFirst("^NO(?=[0-9])", "");
    // Night-service names also carry a leading zero before letters ("0GL-23 (NS)").
    return k.replaceFirst("^0+(?=[0-9A-Z])", "");
  }

  private static String display(String number) {
    return number.replaceAll("\\(\\s*NS\\s*\\)", "").trim().replaceAll("\\s+", " ")
        .replaceFirst("^0+(?=[0-9A-Za-z])", "");
  }

  public boolean isEmpty() {
    return paths.isEmpty();
  }

  public int size() {
    return paths.size();
  }

  public String source() {
    return source;
  }

  public List<Path> paths() {
    return Collections.unmodifiableList(paths);
  }

  public List<Path> paths(String number) {
    return byNumber.getOrDefault(key(number), List.of());
  }

  /** Whether any official path of the route number passes within {@link #ON_PATH_METERS}. */
  public boolean onRoute(String number, double lat, double lng) {
    for (var p : paths(number))
      if (Geometry.project(p.points(), lat, lng).offset() <= ON_PATH_METERS) return true;
    return false;
  }

  private record Near(double offset, double along) {}

  /** Each official path passing within {@code meters} of the point, with the nearest position on it. */
  private Map<Path, Near> near(double lat, double lng, double meters) {
    Map<Path, Near> out = new HashMap<>();
    long y0 = cell(lat), x0 = cell(lng);
    int reach = (int) Math.ceil(meters / 450) ;
    for (long y = y0 - reach; y <= y0 + reach; y++)
      for (long x = x0 - reach; x <= x0 + reach; x++)
        for (var s : grid.getOrDefault(y * 100_000 + x, List.of())) {
          var pts = s.path().points();
          var proj = Geometry.project(List.of(pts.get(s.index() - 1), pts.get(s.index())), lat, lng);
          if (proj.offset() > meters) continue;
          double along = s.path().cumulative()[s.index() - 1] + proj.along();
          var best = out.get(s.path());
          if (best == null || proj.offset() < best.offset()) out.put(s.path(), new Near(proj.offset(), along));
        }
    return out;
  }

  /**
   * Route numbers whose official path the whole drive lies on, in its direction, best fit first.
   * A partial drive (a bus seen for part of its trip) cannot tell apart routes sharing that
   * stretch, but it does rule out every route that leaves it. Null when too short to judge.
   */
  public List<String> consistentNumbers(List<TransitIndex.Point> track) {
    var ranked = fits(track);
    if (ranked == null) return null;
    return ranked.stream()
        .filter(f -> f.trackCoverage() >= MIN_CONSISTENT_COVERAGE && f.stopCoverage() >= 0.8)
        .map(Fit::number)
        .toList();
  }

  /**
   * Best fit per route number, best first; null when the drive is too short to judge. Every
   * ~100 m of the drive is compared with the official paths around it; a path fits when the drive
   * stays on it, moves along it in its direction, covers it end to end, and passes its stops.
   * Routes sharing a corridor are thereby told apart by where they leave it.
   */
  List<Fit> fits(List<TransitIndex.Point> track) {
    var samples = resample(track);
    if (samples.size() * SAMPLE_METERS < MIN_TRACK_METERS) return null;
    Map<Path, List<Double>> hits = new HashMap<>();
    for (var s : samples)
      near(s.lat(), s.lng(), FOLLOW_METERS)
          .forEach((p, n) -> hits.computeIfAbsent(p, k -> new ArrayList<>()).add(n.along()));
    Map<String, Fit> bestByNumber = new HashMap<>();
    for (var e : hits.entrySet()) {
      var p = e.getKey();
      var along = e.getValue();
      double trackCoverage = (double) along.size() / samples.size();
      if (trackCoverage < 0.5) continue;
      int forward = 0, backward = 0;
      for (int i = 1; i < along.size(); i++) {
        double d = along.get(i) - along.get(i - 1);
        if (d > 20) forward++;
        else if (d < -20) backward++;
      }
      if (forward < 2 * backward) continue; // drives the path the wrong way: the other direction
      double lo = Collections.min(along), hi = Collections.max(along);
      int inSpan = 0, passed = 0;
      for (int i = 0; i < p.points().size(); i++) {
        if (p.cumulative()[i] < lo || p.cumulative()[i] > hi) continue;
        inSpan++;
        var stop = p.points().get(i);
        if (nearest(samples, stop.lat(), stop.lng()) <= FOLLOW_METERS + SAMPLE_METERS / 2) passed++;
      }
      if (inSpan == 0 || p.lengthMeters() == 0) continue;
      double spanCoverage = Math.min(1, (hi - lo) / p.lengthMeters());
      double stopCoverage = (double) passed / inSpan;
      var fit = new Fit(p.number(), p.direction(), p.id(), trackCoverage, spanCoverage,
          stopCoverage, trackCoverage * spanCoverage * stopCoverage);
      bestByNumber.merge(key(p.number()), fit, (a, b) -> a.score() >= b.score() ? a : b);
    }
    var ranked = new ArrayList<>(bestByNumber.values());
    ranked.sort(Comparator.comparingDouble(Fit::score).reversed());
    return ranked;
  }

  /** The track as points about {@link #SAMPLE_METERS} apart along it. */
  static List<TransitIndex.Point> resample(List<TransitIndex.Point> track) {
    List<TransitIndex.Point> out = new ArrayList<>();
    if (track.isEmpty()) return out;
    out.add(track.get(0));
    double carried = 0;
    for (int i = 1; i < track.size(); i++) {
      var a = track.get(i - 1);
      var b = track.get(i);
      double len = Geometry.distance(a.lat(), a.lng(), b.lat(), b.lng());
      double at = SAMPLE_METERS - carried;
      while (at <= len) {
        double f = at / len;
        out.add(new TransitIndex.Point(a.lat() + f * (b.lat() - a.lat()), a.lng() + f * (b.lng() - a.lng()), out.size()));
        at += SAMPLE_METERS;
      }
      carried = len - (at - SAMPLE_METERS);
    }
    return out;
  }

  private static double nearest(List<TransitIndex.Point> samples, double lat, double lng) {
    double best = Double.POSITIVE_INFINITY;
    for (var s : samples) {
      // Cheap reject before the exact distance: 0.003 degrees is over 290 m in any direction here.
      if (Math.abs(s.lat() - lat) > 0.003 || Math.abs(s.lng() - lng) > 0.003) continue;
      best = Math.min(best, Geometry.distance(lat, lng, s.lat(), s.lng()));
    }
    return best;
  }
}
