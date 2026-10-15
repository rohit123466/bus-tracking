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
    read(
        "routes.txt",
        r -> {
          String shortName = r.getOrDefault("route_short_name", "").trim();
          String name = r.getOrDefault("route_long_name", "").trim();
          String number = publicNumber(shortName, name);
          String direction = name.endsWith("DOWN") ? "DOWN" : name.endsWith("UP") ? "UP" : "";
          routes.put(
              r.get("route_id"),
              new Route(r.get("route_id"), number, name, direction, shortName.isEmpty()));
        });
    read(
        "stops.txt",
        r -> {
          addStop(
              new Stop(
                  r.get("stop_id"),
                  r.get("stop_name"),
                  Double.parseDouble(r.get("stop_lat")),
                  Double.parseDouble(r.get("stop_lon"))));
        });
    read(
        "trips.txt",
        r ->
            trips.put(
                r.get("trip_id"),
                new Trip(
                    r.get("trip_id"),
                    r.get("route_id"),
                    r.getOrDefault("direction_id", ""),
                    r.getOrDefault("shape_id", ""),
                    new ArrayList<>())));
    read(
        "stop_times.txt",
        r -> {
          Trip t = trips.get(r.get("trip_id"));
          Stop s = stops.get(r.get("stop_id"));
          if (t == null || s == null) return;
          t.visits()
              .add(
                  new Visit(
                      s.id(),
                      Integer.parseInt(r.get("stop_sequence")),
                      seconds(r.get("arrival_time")),
                      seconds(r.get("departure_time"))));
          routesAtStop.computeIfAbsent(s.id(), k -> new HashSet<>()).add(t.routeId());
        });
    // Preserve GTFS stop sequence, including times beyond midnight.
    trips.values().forEach(t -> t.visits().sort(Comparator.comparingInt(Visit::sequence)));
    read(
        "shapes.txt",
        r ->
            shapes
                .computeIfAbsent(r.get("shape_id"), k -> new ArrayList<>())
                .add(
                    new Point(
                        Double.parseDouble(r.get("shape_pt_lat")),
                        Double.parseDouble(r.get("shape_pt_lon")),
                        Integer.parseInt(r.get("shape_pt_sequence")))));
    shapes.values().forEach(p -> p.sort(Comparator.comparingInt(Point::sequence)));
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

  public Bounds bounds(Region region) {
    return Bounds.parse(region == Region.NORTH ? northBounds : northWestBounds);
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

  public static String publicNumber(String shortName, String longName) {
    if (shortName != null && !shortName.isBlank()) return shortName.trim().toUpperCase(Locale.ROOT);
    // Feed-specific fallback: strip only explicit terminal direction suffixes; 534E stays 534E.
    String name = longName == null ? "" : longName.trim().toUpperCase(Locale.ROOT);
    return name.matches("[0-9][A-Z0-9/-]*(UP|DOWN)") ? name.replaceFirst("(UP|DOWN)$", "") : null;
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

  private void read(String file, Consumer<Map<String, String>> consume) throws Exception {
    try (ZipInputStream zip = new ZipInputStream(archive.getInputStream())) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null)
        if (entry.getName().equals(file)) {
          CSVReader reader = new CSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
          String[] header = reader.readNext(), row;
          while ((row = reader.readNext()) != null) {
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < header.length && i < row.length; i++)
              values.put(header[i].replace("\ufeff", "").trim(), row[i].trim());
            consume.accept(values);
          }
          return;
        }
    }
  }
}
