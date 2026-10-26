package com.dtc.bus_tracker.live;

import com.opencsv.CSVReader;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * DTC's published service data beyond the GTFS timetable: the master route list, every route's
 * fare-stage stop list, and the Night, NCR, Express and Destination (non-stop) timetables. Read
 * from the cleaned relational tables, whose route and stop IDs are the GTFS ones in GTFS.zip.
 */
@Component
public class ServiceCatalog {
  public enum Kind { NIGHT, NCR, EXPRESS, DESTINATION }

  /** One public route number with both directions' GTFS route IDs and where else it is listed. */
  public record MasterRoute(
      String id, String key, String code, String category, String agency, String origin,
      String destination, String upRouteId, String downRouteId, List<String> fareStageSheets,
      List<String> serviceIds) {}

  public record FareStageStop(
      int sequence, String name, boolean fareStage, String stopId, Double latitude, Double longitude) {}

  /** One direction of a route as listed in DTC's fare-stage sheets. */
  public record FareStageRoute(
      String sheet, String routeCode, String direction, String gtfsRouteId, String masterRouteId,
      Double distanceKm, List<FareStageStop> stops) {}

  public record Departure(String time, String from, String note) {}

  public record ServiceStop(int sequence, String role, String name, String stopId, Double latitude, Double longitude) {}

  /** A Night, NCR, Express or Destination service with its published departures. */
  public record SpecialService(
      String id, Kind kind, String routeCode, String direction, String origin, String destination,
      String note, List<String> gtfsRouteIds, String masterRouteId, List<Departure> departures,
      List<ServiceStop> stops) {}

  public record Summary(String source, int routes, int fareStageRoutes, Map<Kind, Integer> services) {}

  private final List<MasterRoute> routes = new ArrayList<>();
  private final Map<String, MasterRoute> routesByKey = new HashMap<>();
  private final Map<String, List<FareStageRoute>> fareStagesByRoute = new HashMap<>();
  private final Map<String, SpecialService> services = new LinkedHashMap<>();
  private int fareStageRoutes;
  private String source = "";

  @Value("${transit.service-data-resource:classpath:static/dtc-service-data.zip}")
  private Resource resource;

  public ServiceCatalog() {}

  ServiceCatalog(Resource resource) {
    this.resource = resource;
  }

  @PostConstruct
  public void load() {
    if (resource == null || !resource.exists()) {
      System.out.println("[ServiceCatalog] No service data found; /api/transit/catalog is empty.");
      return;
    }
    try {
      Map<String, byte[]> tables = readTables();
      loadRoutes(tables);
      loadFareStages(tables);
      loadNight(tables);
      loadNcr(tables);
      loadExpress(tables);
      loadDestination(tables);
      source = resource.getFilename();
      System.out.println("[ServiceCatalog] Loaded " + routes.size() + " routes, " + fareStageRoutes
          + " fare-stage route directions and " + services.size() + " special services.");
    } catch (IOException | RuntimeException e) {
      System.err.println("[ServiceCatalog] Could not read the service data: " + e.getMessage());
    }
  }

  public Summary summary() {
    Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
    for (Kind k : Kind.values()) counts.put(k, 0);
    services.values().forEach(s -> counts.merge(s.kind(), 1, Integer::sum));
    return new Summary(source, routes.size(), fareStageRoutes, counts);
  }

  /** Routes whose number or terminals contain the query, optionally in one category. */
  public List<MasterRoute> routes(String query, String category, int limit) {
    String q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
    String qKey = key(q);
    String c = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
    return routes.stream()
        .filter(r -> c.isEmpty() || r.category().toUpperCase(Locale.ROOT).contains(c))
        .filter(r -> q.isEmpty()
            || (!qKey.isEmpty() && r.key().contains(qKey))
            || r.origin().toUpperCase(Locale.ROOT).contains(q)
            || r.destination().toUpperCase(Locale.ROOT).contains(q))
        // Exact number, then numbers starting with it: "102" lists 102, 1020, 102A before 1102.
        .sorted(Comparator.comparingInt((MasterRoute r) -> r.key().equals(qKey) ? 0 : r.key().startsWith(qKey) ? 1 : 2)
            .thenComparing(MasterRoute::key))
        .limit(Math.max(1, limit))
        .toList();
  }

  /** By master route id (MR_0040) or route number in any spelling (102A, DS-1, 0GL-23(NS)). */
  public Optional<MasterRoute> route(String idOrNumber) {
    if (idOrNumber == null) return Optional.empty();
    return Optional.ofNullable(routesByKey.get(key(idOrNumber)));
  }

  public List<FareStageRoute> fareStages(MasterRoute route) {
    return fareStagesByRoute.getOrDefault(route.id(), List.of());
  }

  public List<SpecialService> services(Kind kind) {
    return services.values().stream().filter(s -> kind == null || s.kind() == kind).toList();
  }

  public Optional<SpecialService> service(String id) {
    return Optional.ofNullable(services.get(id));
  }

  public List<SpecialService> services(MasterRoute route) {
    return route.serviceIds().stream().map(services::get).filter(Objects::nonNull).toList();
  }

  /** Uppercase letters and digits only: "DS-1", "ds1" and "DS 1" are the same route. */
  static String key(String raw) {
    return raw == null ? "" : raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
  }

  // ── Loading ──────────────────────────────────────────────────────────────────────────────

  private void loadRoutes(Map<String, byte[]> tables) {
    for (var r : table(tables, "master_routes_unified.csv")) {
      List<String> serviceIds = new ArrayList<>();
      serviceIds.addAll(list(r.get("express_route_ids")));
      serviceIds.addAll(list(r.get("ds_route_id")));
      serviceIds.addAll(list(r.get("night_route_ids")));
      serviceIds.addAll(list(r.get("ncr_route_ids")));
      var route = new MasterRoute(
          r.get("master_route_id"), key(r.get("master_route_key")), text(r.get("canonical_route_code")),
          text(r.get("service_category")), text(r.get("agency_id")), text(r.get("origin_terminal")),
          text(r.get("destination_terminal")), id(r.get("gtfs_up_route_id")), id(r.get("gtfs_down_route_id")),
          list(r.get("fare_stage_sheets")), List.copyOf(serviceIds));
      routes.add(route);
      routesByKey.put(key(route.id()), route);
      routesByKey.putIfAbsent(route.key(), route);
      routesByKey.putIfAbsent(key(route.code()), route);
    }
  }

  private void loadFareStages(Map<String, byte[]> tables) {
    Map<String, Double> distances = new HashMap<>();
    for (var r : table(tables, "fare_stage_routes_summary.csv"))
      distances.put(r.get("category_sheet") + "|" + r.get("route_header"), number(r.get("total_distance_km")));
    // UP and DN rows interleave (the sheets list them side by side), and a sheet can list the same
    // route twice, so a direction's list starts over wherever its stop numbering restarts.
    Map<String, FareStageRoute> byList = new LinkedHashMap<>();
    Map<String, List<FareStageStop>> stops = new HashMap<>();
    Map<String, Integer> listOfHeader = new HashMap<>(), lastSequence = new HashMap<>();
    Map<String, String> names = new HashMap<>(); // stop names repeat across ~100k rows
    for (var r : table(tables, "fare_stages_stops.csv")) {
      String header = r.get("category_sheet") + "|" + r.get("route_header");
      int sequence = (int) Math.round(number(r.get("stop_sequence"), 0));
      Integer last = lastSequence.put(header, sequence);
      if (last == null || sequence <= last) listOfHeader.merge(header, 1, Integer::sum);
      String list = header + "#" + listOfHeader.get(header);
      byList.computeIfAbsent(list, l -> new FareStageRoute(
          r.get("category_sheet"), text(r.get("route_code")), text(r.get("direction")),
          id(r.get("gtfs_route_id")), text(r.get("master_route_id")), distances.get(header), List.of()));
      stops.computeIfAbsent(list, l -> new ArrayList<>()).add(new FareStageStop(
          sequence,
          names.computeIfAbsent(text(r.get("stop_name")), n -> n),
          "TRUE".equalsIgnoreCase(r.get("is_fare_stage")),
          id(r.get("gtfs_stop_id")), number(r.get("stop_lat")), number(r.get("stop_lon"))));
    }
    byList.forEach((list, route) -> {
      var full = new FareStageRoute(route.sheet(), route.routeCode(), route.direction(), route.gtfsRouteId(),
          route.masterRouteId(), route.distanceKm(), List.copyOf(stops.get(list)));
      var listed = fareStagesByRoute.computeIfAbsent(route.masterRouteId(), k -> new ArrayList<>());
      if (listed.contains(full)) return; // the same list printed twice in one sheet
      listed.add(full);
      fareStageRoutes++;
    });
  }

  private void loadNight(Map<String, byte[]> tables) {
    var departures = departures(tables, "night_bus_timetables.csv", "night_route_id",
        r -> new Departure(text(r.get("departure_time_24h")), text(r.get("origin_terminal")), text(r.get("service_note"))));
    for (var r : table(tables, "night_bus_routes.csv"))
      put(new SpecialService(
          r.get("night_route_id"), Kind.NIGHT, text(r.get("route_code")), text(r.get("direction")),
          text(r.get("origin_terminal")), text(r.get("destination_terminal")), text(r.get("service_note")),
          list(id(r.get("gtfs_route_id"))), text(r.get("master_route_id")),
          departures.getOrDefault(r.get("night_route_id"), List.of()), List.of()));
  }

  private void loadNcr(Map<String, byte[]> tables) {
    var departures = departures(tables, "ncr_bus_timetables.csv", "ncr_route_id",
        r -> new Departure(text(r.get("departure_time_24h")), text(r.get("origin_terminal")),
            "Regular".equalsIgnoreCase(text(r.get("bus_or_trip_type"))) ? "" : text(r.get("bus_or_trip_type"))));
    for (var r : table(tables, "ncr_bus_routes.csv"))
      put(new SpecialService(
          r.get("ncr_route_id"), Kind.NCR, text(r.get("route_name")), text(r.get("direction")),
          text(r.get("origin_terminal")), text(r.get("destination_terminal")), text(r.get("service_note")),
          list(id(r.get("gtfs_route_id"))), text(r.get("master_route_id")),
          departures.getOrDefault(r.get("ncr_route_id"), List.of()), List.of()));
  }

  private void loadExpress(Map<String, byte[]> tables) {
    var departures = departures(tables, "express_route_trips.csv", "express_route_id",
        r -> new Departure(text(r.get("departure_time_24h")), text(r.get("origin_terminal")), text(r.get("period"))));
    var stops = stops(tables, "express_route_stops.csv", "express_route_id");
    for (var r : table(tables, "express_routes.csv")) {
      String fare = text(r.get("non_ac_fare_inr")).isEmpty() ? ""
          : "Fare Rs " + text(r.get("non_ac_fare_inr")) + " (non-AC), Rs " + text(r.get("ac_fare_inr")) + " (AC)";
      put(new SpecialService(
          r.get("express_route_id"), Kind.EXPRESS, text(r.get("route_no")), "",
          text(r.get("origin_terminal")), text(r.get("destination_terminal")), fare,
          list(id(r.get("gtfs_up_route_id")), id(r.get("gtfs_down_route_id"))), text(r.get("master_route_id")),
          departures.getOrDefault(r.get("express_route_id"), List.of()),
          stops.getOrDefault(r.get("express_route_id"), List.of())));
    }
  }

  private void loadDestination(Map<String, byte[]> tables) {
    var stops = stops(tables, "destination_bus_stops.csv", "ds_route_id");
    for (var r : table(tables, "destination_bus_routes.csv")) {
      List<Departure> departures = new ArrayList<>();
      if (!text(r.get("morning_departure_from_origin_24h")).isEmpty())
        departures.add(new Departure(text(r.get("morning_departure_from_origin_24h")), text(r.get("origin_terminal")), "Morning"));
      if (!text(r.get("evening_departure_from_destination_24h")).isEmpty())
        departures.add(new Departure(text(r.get("evening_departure_from_destination_24h")), text(r.get("destination_terminal")), "Evening"));
      put(new SpecialService(
          r.get("ds_route_id"), Kind.DESTINATION, text(r.get("route_no")), "",
          text(r.get("origin_terminal")), text(r.get("destination_terminal")), text(r.get("bus_type")),
          list(id(r.get("gtfs_up_route_id")), id(r.get("gtfs_down_route_id"))), text(r.get("master_route_id")),
          List.copyOf(departures), stops.getOrDefault(r.get("ds_route_id"), List.of())));
    }
  }

  private void put(SpecialService s) {
    services.put(s.id(), s);
  }

  private static Map<String, List<Departure>> departures(
      Map<String, byte[]> tables, String file, String routeColumn,
      java.util.function.Function<Row, Departure> row) {
    Map<String, List<Departure>> out = new HashMap<>();
    for (var r : table(tables, file)) out.computeIfAbsent(r.get(routeColumn), k -> new ArrayList<>()).add(row.apply(r));
    // Published order (times past midnight follow the evening ones), as listed in the timetable.
    out.replaceAll((k, v) -> List.copyOf(v));
    return out;
  }

  private static Map<String, List<ServiceStop>> stops(
      Map<String, byte[]> tables, String file, String routeColumn) {
    Map<String, List<ServiceStop>> out = new HashMap<>();
    for (var r : table(tables, file))
      out.computeIfAbsent(r.get(routeColumn), k -> new ArrayList<>()).add(new ServiceStop(
          (int) Math.round(number(r.get("stop_sequence"), 0)), text(r.get("stop_role")), text(r.get("stop_name")),
          id(r.get("gtfs_stop_id")), number(r.get("stop_lat")), number(r.get("stop_lon"))));
    out.replaceAll((k, v) -> {
      v.sort(Comparator.comparingInt(ServiceStop::sequence));
      return List.copyOf(v);
    });
    return out;
  }

  /** One CSV row, read by column name. */
  private record Row(Map<String, Integer> columns, String[] values) {
    String get(String column) {
      Integer i = columns.get(column);
      return i == null || i >= values.length ? null : values[i].trim();
    }
  }

  /** Raw CSV bytes per file: ~12 MB, where parsed rows held as maps would not fit the heap. */
  private Map<String, byte[]> readTables() throws IOException {
    Map<String, byte[]> tables = new HashMap<>();
    try (var zip = new ZipInputStream(resource.getInputStream())) {
      for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
        String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
        if (name.endsWith(".csv")) tables.put(name, zip.readAllBytes());
      }
    }
    return tables;
  }

  /** Streams a table's rows, so only the row being read is held in memory. */
  private static Iterable<Row> table(Map<String, byte[]> tables, String name) {
    byte[] bytes = tables.get(name);
    if (bytes == null) return List.of();
    return () -> {
      Iterator<String[]> lines =
          new CSVReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)).iterator();
      if (!lines.hasNext()) return Collections.emptyIterator();
      String[] header = lines.next();
      Map<String, Integer> columns = new HashMap<>();
      for (int i = 0; i < header.length; i++) columns.put(header[i].replace("\ufeff", "").trim(), i);
      return new Iterator<>() {
        public boolean hasNext() {
          return lines.hasNext();
        }

        public Row next() {
          return new Row(columns, lines.next());
        }
      };
    };
  }

  private static String text(String raw) {
    return raw == null ? "" : raw.trim();
  }

  /** The tables write numeric IDs as floats ("1938.0") wherever a column had blanks. */
  static String id(String raw) {
    String t = text(raw);
    if (t.endsWith(".0")) t = t.substring(0, t.length() - 2);
    return t.isEmpty() || t.equalsIgnoreCase("nan") ? null : t;
  }

  private static Double number(String raw) {
    try {
      return text(raw).isEmpty() ? null : Double.valueOf(text(raw));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static double number(String raw, double fallback) {
    Double d = number(raw);
    return d == null ? fallback : d;
  }

  /** Comma-separated cell to a list; null and blank entries dropped. */
  private static List<String> list(String... cells) {
    List<String> out = new ArrayList<>();
    for (String cell : cells)
      if (cell != null)
        for (String part : cell.split(","))
          if (!part.isBlank()) out.add(part.trim());
    return List.copyOf(out);
  }
}
