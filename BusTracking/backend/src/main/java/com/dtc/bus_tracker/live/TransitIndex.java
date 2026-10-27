package com.dtc.bus_tracker.live;

import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** Immutable, full-feed lookup index, independent of the legacy regional database import. */
@Component
public class TransitIndex {
  public record Stop(String id, String name, double lat, double lng) {}

  public record Route(String id, String number, String name, String direction, boolean inferred) {}

  public record Visit(String stopId, int sequence, int arrival, int departure) {}

  public record Point(double lat, double lng, int sequence) {}

  public record Trip(
      String id, String routeId, String direction, String shapeId, List<Visit> visits) {}

  /**
   * A trip's visits packed as {stop, sequence, arrival, departure} ints, materialised on access.
   * stop_times has ~3.7M rows: one Visit object per row held ~200 MB of heap and got the 512 MB
   * hosted instance OOM-killed.
   */
  private static final class PackedVisits extends AbstractList<Visit> implements RandomAccess {
    private final String[] stopIds;
    private final int[] rows;

    PackedVisits(String[] stopIds, int[] rows) {
      this.stopIds = stopIds;
      this.rows = rows;
    }

    @Override
    public Visit get(int i) {
      int o = Objects.checkIndex(i, size()) * 4;
      return new Visit(stopIds[rows[o]], rows[o + 1], rows[o + 2], rows[o + 3]);
    }

    @Override
    public int size() {
      return rows.length / 4;
    }
  }

  /**
   * A shape's points packed as parallel arrays, materialised on access. shapes.txt has ~1.1M
   * points: one Point object (plus list slot) each held ~40 MB of heap, about a fifth of the
   * hosted instance's heap.
   */
  private static final class PackedPoints extends AbstractList<Point> implements RandomAccess {
    private final double[] latLng;
    private final int[] sequence;

    PackedPoints(double[] latLng, int[] sequence) {
      this.latLng = latLng;
      this.sequence = sequence;
    }

    @Override
    public Point get(int i) {
      Objects.checkIndex(i, sequence.length);
      return new Point(latLng[i * 2], latLng[i * 2 + 1], sequence[i]);
    }

    @Override
    public int size() {
      return sequence.length;
    }
  }

  /** Growable buffer collecting one shape's points while loading. */
  private static final class PointBuffer {
    private double[] latLng = new double[128];
    private int[] sequence = new int[64];
    private int length;

    void add(double lat, double lng, int seq) {
      if (length == sequence.length) {
        sequence = Arrays.copyOf(sequence, length * 2);
        latLng = Arrays.copyOf(latLng, length * 4);
      }
      latLng[length * 2] = lat;
      latLng[length * 2 + 1] = lng;
      sequence[length++] = seq;
    }

    /** Points in shape_pt_sequence order (stable for equal sequences), trimmed to size. */
    PackedPoints sortedBySequence() {
      Integer[] order = new Integer[length];
      for (int i = 0; i < length; i++) order[i] = i;
      Arrays.sort(order, Comparator.comparingInt(i -> sequence[i]));
      double[] ll = new double[length * 2];
      int[] seq = new int[length];
      for (int i = 0; i < length; i++) {
        ll[i * 2] = latLng[order[i] * 2];
        ll[i * 2 + 1] = latLng[order[i] * 2 + 1];
        seq[i] = sequence[order[i]];
      }
      return new PackedPoints(ll, seq);
    }
  }

  /** Growable int buffer collecting one trip's packed stop_times rows while loading. */
  private static final class RowBuffer {
    private int[] rows = new int[64];
    private int length;

    void add(int stop, int sequence, int arrival, int departure) {
      if (length + 4 > rows.length) rows = Arrays.copyOf(rows, rows.length * 2);
      rows[length++] = stop;
      rows[length++] = sequence;
      rows[length++] = arrival;
      rows[length++] = departure;
    }

    /** Rows in GTFS stop sequence (stable for equal sequences), trimmed to size. */
    int[] sortedBySequence() {
      Integer[] order = new Integer[length / 4];
      for (int i = 0; i < order.length; i++) order[i] = i;
      Arrays.sort(order, Comparator.comparingInt(i -> rows[i * 4 + 1]));
      int[] sorted = new int[length];
      for (int i = 0; i < order.length; i++) System.arraycopy(rows, order[i] * 4, sorted, i * 4, 4);
      return sorted;
    }
  }

  public final Map<String, Stop> stops = new HashMap<>();
  public final Map<String, Route> routes = new HashMap<>();
  public final Map<String, Trip> trips = new HashMap<>();
  public final Map<String, List<Point>> shapes = new HashMap<>();
  public final Map<String, Set<String>> routesAtStop = new HashMap<>();
  private final Map<String, List<Stop>> cells = new HashMap<>();

  /** Administrative areas used by the Live Map route filter. */
  public enum Region {
    NORTH,
    NORTH_WEST
  }

  /** Axis-aligned box in {@code minLat,maxLat,minLng,maxLng} order. */
  public record Bounds(double minLat, double maxLat, double minLng, double maxLng) {
    public boolean contains(double lat, double lng) {
      return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }

    static Bounds parse(String csv) {
      String[] p = csv.split(",");
      if (p.length != 4) throw new IllegalArgumentException("Region bounds need 4 values: " + csv);
      return new Bounds(
          Double.parseDouble(p[0].trim()),
          Double.parseDouble(p[1].trim()),
          Double.parseDouble(p[2].trim()),
          Double.parseDouble(p[3].trim()));
    }
  }

  /**
   * Approximate extents of the two districts; configurable because GTFS carries no district
   * polygons. A route belongs to a region when at least one of its stops lies inside the box.
   */
  @Value("${transit.region.north:28.655,28.76,77.17,77.27}")
  private String northBounds = "28.655,28.76,77.17,77.27";

  @Value("${transit.region.north-west:28.69,28.90,76.83,77.18}")
  private String northWestBounds = "28.69,28.90,76.83,77.18";

  /** GTFS route_id -> regions in which the route has at least one stop. Absent = neither. */
  public final Map<String, EnumSet<Region>> routeRegions = new HashMap<>();

  @Value("${transit.gtfs-resource:classpath:static/GTFS.zip}")
  private Resource archive;

  public String warning;

  @PostConstruct
  public void load() throws Exception {
    // Each later file needs the earlier ones (stop_times resolves trips and stops), so they are
    // consumed in this order; see readInOrder.
    Map<String, Consumer<Row>> files = new LinkedHashMap<>();
    files.put(
        "routes.txt",
        r -> {
          String shortName = r.getOrDefault("route_short_name", "").trim();
          String name = r.getOrDefault("route_long_name", "").trim();
          String number = publicNumber(shortName, name);
          String direction = direction(name.isEmpty() ? shortName : name);
          routes.put(
              r.get("route_id"),
              new Route(r.get("route_id"), number, name, direction, shortName.isEmpty()));
        });
    files.put(
        "stops.txt",
        r -> {
          addStop(
              new Stop(
                  r.get("stop_id"),
                  r.get("stop_name"),
                  Double.parseDouble(r.get("stop_lat")),
                  Double.parseDouble(r.get("stop_lon"))));
        });
    files.put(
        "trips.txt",
        r ->
            trips.put(
                r.get("trip_id"),
                new Trip(
                    r.get("trip_id"),
                    r.get("route_id"),
                    r.getOrDefault("direction_id", ""),
                    r.getOrDefault("shape_id", ""),
                    List.of())));
    Map<String, Integer> stopIndex = new HashMap<>();
    List<String> stopIds = new ArrayList<>();
    Map<String, RowBuffer> pending = new HashMap<>();
    files.put(
        "stop_times.txt",
        r -> {
          Trip t = trips.get(r.get("trip_id"));
          Stop s = stops.get(r.get("stop_id"));
          if (t == null || s == null) return;
          int stop =
              stopIndex.computeIfAbsent(
                  s.id(),
                  k -> {
                    stopIds.add(k);
                    return stopIds.size() - 1;
                  });
          pending
              .computeIfAbsent(t.id(), k -> new RowBuffer())
              .add(
                  stop,
                  Integer.parseInt(r.get("stop_sequence")),
                  seconds(r.get("arrival_time")),
                  seconds(r.get("departure_time")));
          routesAtStop.computeIfAbsent(s.id(), k -> new HashSet<>()).add(t.routeId());
        });
    Map<String, PointBuffer> shapePoints = new HashMap<>();
    files.put(
        "shapes.txt",
        r ->
            shapePoints
                .computeIfAbsent(r.get("shape_id"), k -> new PointBuffer())
                .add(
                    Double.parseDouble(r.get("shape_pt_lat")),
                    Double.parseDouble(r.get("shape_pt_lon")),
                    Integer.parseInt(r.get("shape_pt_sequence"))));
    readInOrder(files);
    // Preserve GTFS stop sequence, including times beyond midnight.
    String[] stopTable = stopIds.toArray(String[]::new);
    trips.replaceAll(
        (id, t) -> {
          RowBuffer rows = pending.remove(id);
          return rows == null
              ? t
              : new Trip(
                  t.id(),
                  t.routeId(),
                  t.direction(),
                  t.shapeId(),
                  new PackedVisits(stopTable, rows.sortedBySequence()));
        });
    shapePoints.forEach((id, points) -> shapes.put(id, points.sortedBySequence()));
    if (routes.isEmpty() || stops.isEmpty())
      throw new IllegalStateException(
          "GTFS route/stop mappings are empty; resolve Git LFS and configure a valid GTFS"
              + " archive.");
    computeRegions();
    warning =
        shapes.isEmpty()
            ? "GTFS has no shapes: along-route distance and geometry-based ETA are unavailable."
                  + " Fresh TripUpdates may still supply arrival predictions."
            : null;
  }

  /** Registers a stop in both the lookup map and the spatial grid used by {@link #nearby}. */
  public void addStop(Stop s) {
    stops.put(s.id(), s);
    cells.computeIfAbsent(cell(s.lat(), s.lng()), k -> new ArrayList<>()).add(s);
  }

  // Parsed once: bounds are checked for every stop and bus on each request.
  private volatile Bounds parsedNorth, parsedNorthWest;

  public Bounds bounds(Region region) {
    if (region == Region.NORTH) {
      if (parsedNorth == null) parsedNorth = Bounds.parse(northBounds);
      return parsedNorth;
    }
    if (parsedNorthWest == null) parsedNorthWest = Bounds.parse(northWestBounds);
    return parsedNorthWest;
  }

  /** Regions whose box contains the point; empty when the stop is in neither. */
  public EnumSet<Region> regionsAt(double lat, double lng) {
    EnumSet<Region> result = EnumSet.noneOf(Region.class);
    for (Region r : Region.values()) if (bounds(r).contains(lat, lng)) result.add(r);
    return result;
  }

  /**
   * Derives {@link #routeRegions} from the actual stop-to-route mapping (stop_times), not from any
   * vehicle position, so a route is eligible even when its buses are currently elsewhere.
   */
  public void computeRegions() {
    routeRegions.clear();
    Map<Region, Bounds> boxes = new EnumMap<>(Region.class);
    for (Region r : Region.values()) boxes.put(r, bounds(r));
    for (var entry : routesAtStop.entrySet()) {
      Stop s = stops.get(entry.getKey());
      if (s == null) continue;
      EnumSet<Region> here = EnumSet.noneOf(Region.class);
      boxes.forEach((r, b) -> { if (b.contains(s.lat(), s.lng())) here.add(r); });
      if (here.isEmpty()) continue;
      for (String routeId : entry.getValue())
        routeRegions.computeIfAbsent(routeId, k -> EnumSet.noneOf(Region.class)).addAll(here);
    }
  }

  /** Direction suffix of a DTC route name, with an optional variant number: 116AUP2, 534DOWN. */
  private static final String DIRECTION_SUFFIX = "(UP|DOWN)[0-9]*$";

  public static String publicNumber(String shortName, String longName) {
    if (shortName != null && !shortName.isBlank()) {
      // The 2025 DTC/DoT feed repeats the direction in route_short_name: 102ADOWN, D-12UP, 116AUP2.
      String name = shortName.trim().toUpperCase(Locale.ROOT);
      String number = name.replaceFirst(DIRECTION_SUFFIX, "");
      return number.isEmpty() ? name : number;
    }
    // Feed-specific fallback: strip only explicit terminal direction suffixes; 534E stays 534E.
    String name = longName == null ? "" : longName.trim().toUpperCase(Locale.ROOT);
    return name.matches("[0-9][A-Z0-9/-]*" + DIRECTION_SUFFIX) ? name.replaceFirst(DIRECTION_SUFFIX, "") : null;
  }

  public static String direction(String routeName) {
    String name = routeName == null ? "" : routeName.trim().toUpperCase(Locale.ROOT);
    return name.matches(".*DOWN[0-9]*") ? "DOWN" : name.matches(".*UP[0-9]*") ? "UP" : "";
  }

  public static int seconds(String raw) {
    if (raw == null || raw.isBlank()) return -1;
    String[] p = raw.split(":");
    return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]);
  }

  public List<Stop> nearby(double lat, double lng, double radius) {
    List<Stop> result = new ArrayList<>();
    int y = (int) Math.floor(lat * 100), x = (int) Math.floor(lng * 100);
    int dy = (int) Math.ceil(radius / 1100) + 1;
    int dx = (int) Math.ceil(radius / (1100 * Math.max(.1, Math.cos(Math.toRadians(lat))))) + 1;
    for (int a = y - dy; a <= y + dy; a++)
      for (int b = x - dx; b <= x + dx; b++)
        for (Stop s : cells.getOrDefault(a + ":" + b, List.of()))
          if (Geometry.distance(lat, lng, s.lat(), s.lng()) <= radius) result.add(s);
    result.sort(Comparator.comparingDouble(s -> Geometry.distance(lat, lng, s.lat(), s.lng())));
    return result;
  }

  private static String cell(double lat, double lng) {
    return (int) Math.floor(lat * 100) + ":" + (int) Math.floor(lng * 100);
  }

  /** One CSV row, looked up by column name; values are trimmed, missing columns are null. */
  static final class Row {
    private final Map<String, Integer> columns;
    private String[] values;

    Row(String[] header) {
      columns = new HashMap<>();
      for (int i = 0; i < header.length; i++) columns.put(header[i].replace("\ufeff", "").trim(), i);
    }

    public String get(String column) {
      Integer i = columns.get(column);
      return i == null || i >= values.length ? null : values[i].trim();
    }

    public String getOrDefault(String column, String fallback) {
      String v = get(column);
      return v == null ? fallback : v;
    }
  }

  /**
   * Reads the files in the map's order in one pass over the archive when it stores them in that
   * order (ZipInputStream cannot seek, so reading each file separately re-inflated every earlier
   * entry, ~90 MB of stop_times to reach shapes). A file stored out of order is read afterwards,
   * together with every file after it, so each still sees the ones before it loaded.
   */
  private void readInOrder(Map<String, Consumer<Row>> files) throws Exception {
    List<String> order = new ArrayList<>(files.keySet());
    int next = 0;
    try (ZipInputStream zip = new ZipInputStream(archive.getInputStream())) {
      ZipEntry entry;
      while (next < order.size() && (entry = zip.getNextEntry()) != null)
        if (entry.getName().equals(order.get(next))) consume(zip, files.get(order.get(next++)));
    }
    for (; next < order.size(); next++) read(order.get(next), files.get(order.get(next)));
  }

  private void read(String file, Consumer<Row> consume) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(archive.getInputStream())) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().equals(file)) {
          consume(zip, consume);
          return;
        }
    }
  }

  private static void consume(ZipInputStream zip, Consumer<Row> consume) throws Exception {
    // Not closed: that would close the archive stream mid-pass.
    CSVReader reader = new CSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
    String[] header = reader.readNext(), row;
    if (header == null) return;
    Row current = new Row(header);
    while ((row = reader.readNext()) != null) {
      current.values = row;
      consume.accept(current);
    }
  }
}
