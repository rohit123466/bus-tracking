package com.dtc.bus_tracker.live;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiveTransitService {
  /**
   * A live vehicle position, enriched with the authoritative GTFS public route number.
   * {@code speedKmh} is present when the feed or derived movement provides it.
   * {@code routeMapping} / {@code matchedRouteIds} are only populated on per-route queries.
   *
   * <p>Diagnostic fields (always set, available in JSON response):
   * <ul>
   *   <li>{@code tripId} — GTFS trip_id from the realtime feed</li>
   *   <li>{@code mappingSource} — how the public route number was resolved</li>
   *   <li>{@code distanceFromShapeMeters} — vehicle's perpendicular distance from its assigned
   *       route shape; null when shapes are unavailable</li>
   *   <li>{@code offRouteFlag} — true when the vehicle is &gt; OFF_ROUTE_THRESHOLD_METERS from
   *       its assigned shape (GPS noise or genuine diversion, NOT a mapping error)</li>
   * </ul>
   */
  public record Bus(
      String vehicleId,
      String routeId,
      String routeCode,
      String direction,
      String tripId,
      double busLatitude,
      double busLongitude,
      Double speedKmh,
      long lastUpdatedEpochSeconds,
      // ── Diagnostic fields ────────────────────────────────────────────────
      /** How the public route number was resolved: TRIP_ID_LOOKUP | ROUTE_ID_FALLBACK | INFERRED */
      String mappingSource,
      /** Vehicle's perpendicular offset from its GTFS shape (metres). Null = no shape. */
      Double distanceFromShapeMeters,
      /** True when distanceFromShapeMeters exceeds the off-route threshold. */
      boolean offRouteFlag) {}

  /** Whether the requested public route number maps to known GTFS route IDs. */
  public enum RouteMappingStatus { MAPPED, UNMAPPED, UNKNOWN }

  /**
   * Full live-search result with backwards-compatible fields plus new diagnostic fields.
   * <p>New: {@code routeMapping}, {@code matchedRouteIds}, {@code feedAvailable},
   * {@code requestedRouteNumber}.
   */
  public record LiveResult(
      List<Bus> buses,
      List<String> warnings,
      long fetchedAt,
      RouteMappingStatus routeMapping,
      List<String> matchedRouteIds,
      boolean feedAvailable,
      String requestedRouteNumber) {}

  /**
   * A live bus found near a user-supplied GPS location. Includes distance from the user.
   * This uses the GTFS realtime feed directly — not the legacy BusLocationStore —
   * so route numbers are always official GTFS identifiers.
   */
  public record NearbyBus(
      String vehicleId,
      String routeId,
      String routeCode,
      String direction,
      String tripId,
      double busLatitude,
      double busLongitude,
      Double speedKmh,
      double distanceMeters,
      long lastUpdatedEpochSeconds) {}

  public record NearbyResult(
      List<NearbyBus> buses,
      List<String> warnings,
      long fetchedAt,
      double radiusMeters,
      boolean feedAvailable) {}

  public record Arrival(
      String routeNumber,
      String vehicleId,
      String tripId,
      Double distanceMeters,
      Double etaMinutes,
      long lastGpsUpdate,
      String predictionAvailability,
      String reason,
      Integer remainingStops) {
    /** Backwards-compatible constructor without remainingStops. */
    public Arrival(String routeNumber, String vehicleId, String tripId,
        Double distanceMeters, Double etaMinutes, long lastGpsUpdate,
        String predictionAvailability, String reason) {
      this(routeNumber, vehicleId, tripId, distanceMeters, etaMinutes,
           lastGpsUpdate, predictionAvailability, reason, null);
    }
  }

  public record Arrivals(List<Arrival> arrivals, List<String> warnings) {}

  private final TransitIndex index;
  private final RealtimeSnapshot live;

  public LiveTransitService(TransitIndex index, RealtimeSnapshot live) {
    this.index = index;
    this.live = live;
  }

  /**
   * Vehicles whose perpendicular distance from their assigned route shape exceeds
   * this threshold are flagged as off-route. They are still returned in the
   * response (genuine diversions happen) but {@code offRouteFlag} is set to true
   * so the UI can highlight them. Adjust via {@code transit.off-route-threshold-m}
   * when required; 500 m accommodates GPS noise and minor road deviations.
   */
  private static final double OFF_ROUTE_THRESHOLD_METERS = 500.0;

  public TransitIndex.Route route(RealtimeSnapshot.Vehicle v) {
    var trip = index.trips.get(v.tripId());
    if (trip != null && !v.routeId().isBlank() && !trip.routeId().equals(v.routeId())) return null;
    return index.routes.get(trip != null ? trip.routeId() : v.routeId());
  }

  /**
   * Returns the mapping source string for diagnostic logging.
   * "TRIP_ID_LOOKUP"  — resolved via trips.txt (authoritative)
   * "ROUTE_ID_FALLBACK" — trip_id absent or unknown; used vehicle's route_id directly
   * "INFERRED"        — route_short_name was empty; number extracted from route_long_name
   */
  private String mappingSource(RealtimeSnapshot.Vehicle v, TransitIndex.Route r) {
    if (r == null) return "UNRESOLVED";
    if (index.trips.containsKey(v.tripId())) return r.inferred() ? "INFERRED" : "TRIP_ID_LOOKUP";
    return r.inferred() ? "INFERRED" : "ROUTE_ID_FALLBACK";
  }

  /**
   * Computes the vehicle's perpendicular distance from its assigned GTFS shape.
   * Returns null when no shape is available for this trip.
   */
  private Double distanceFromShape(RealtimeSnapshot.Vehicle v) {
    var trip = index.trips.get(v.tripId());
    if (trip == null) return null;
    var shape = index.shapes.get(trip.shapeId());
    if (shape == null || shape.size() < 2) return null;
    return Geometry.project(shape, v.lat(), v.lng()).offset();
  }

  /** Returns the current live vehicle snapshot (unfiltered). */
  public Collection<RealtimeSnapshot.Vehicle> liveVehicles() {
    return live.vehicles().values();
  }

  /** Returns true when the realtime feed has been received and is not stale. */
  public boolean feedAvailable() {
    return live.feedError == null
        && !(live.fetchedAt > 0 && Instant.now().getEpochSecond() - live.fetchedAt > 120);
  }

  public LiveResult buses(String number) {
    String query = number == null ? "" : number.trim().toUpperCase(Locale.ROOT);

    // Resolve official GTFS route_id(s) for the requested public number.
    // Internal database PKs, row indices, or unverified conversions are never used.
    List<String> matchedRouteIds = query.isEmpty()
        ? List.of()
        : index.routes.values().stream()
            .filter(r -> query.equals(r.number()))
            .map(TransitIndex.Route::id)
            .toList();
    RouteMappingStatus mappingStatus = query.isEmpty() ? RouteMappingStatus.UNKNOWN
        : matchedRouteIds.isEmpty() ? RouteMappingStatus.UNMAPPED : RouteMappingStatus.MAPPED;

    List<Bus> out = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    int unmapped = 0, stale = 0;
    boolean inferred = false;
    for (var v : live.vehicles().values()) {
      if (!RealtimeSnapshot.fresh(v.timestamp())) {
        stale++;
        continue;
      }
      var r = route(v);
      if (r == null || r.number() == null) {
        unmapped++;
        continue;
      }
      // Exact match: "106" must not match "106A", "160", or "206".
      if (!query.isEmpty() && !query.equals(r.number())) continue;
      if (live.cancelled(v.tripId())) continue;
      inferred |= r.inferred();

      // ── Diagnostic fields ───────────────────────────────────────────────
      String src = mappingSource(v, r);
      Double distFromShape = distanceFromShape(v);
      boolean offRoute = distFromShape != null && distFromShape > OFF_ROUTE_THRESHOLD_METERS;

      // Dev-mode diagnostic log — does NOT log API keys or secrets.
      System.out.printf(
          "[LiveTransit] ACCEPTED vehicle=%s requestedRoute=%s resolvedRouteId=%s tripId=%s"
              + " direction=%s lat=%.6f lng=%.6f gpsAge=%ds mappingSource=%s distFromShape=%s offRoute=%b%n",
          v.id(), query.isEmpty() ? "(all)" : query, r.id(), v.tripId(),
          r.direction().isEmpty() ? v.direction() : r.direction(),
          v.lat(), v.lng(),
          java.time.Instant.now().getEpochSecond() - v.timestamp(),
          src,
          distFromShape != null ? String.format("%.0fm", distFromShape) : "n/a",
          offRoute);

      out.add(
          new Bus(
              v.id(),
              r.id(),
              r.number(),
              r.direction().isEmpty() ? v.direction() : r.direction(),
              v.tripId(),
              v.lat(),
              v.lng(),
              v.speedKmh(),
              v.timestamp(),
              src,
              distFromShape,
              offRoute));
    }
    out.sort(Comparator.comparing(Bus::vehicleId));
    if (unmapped > 0)
      warnings.add(
          unmapped + " live vehicles have missing or conflicting GTFS mappings and are excluded.");
    if (stale > 0) warnings.add(stale + " stale or undated vehicle positions excluded.");
    if (inferred)
      warnings.add(
          "Public route number resolved from this feed's route_long_name because route_short_name"
              + " is empty.");
    if (mappingStatus == RouteMappingStatus.UNMAPPED)
      warnings.add(
          "No GTFS public-route mapping for " + query + ". Unrelated vehicles are not shown.");
    boolean feedAvail = live.feedError == null;
    if (!feedAvail) warnings.add(live.feedError);
    if (live.fetchedAt > 0 && Instant.now().getEpochSecond() - live.fetchedAt > 120) {
      warnings.add("Realtime feed is stale or unavailable.");
      feedAvail = false;
    }
    return new LiveResult(out, warnings, live.fetchedAt,
        mappingStatus, matchedRouteIds, feedAvail, query);
  }

  /**
   * Returns all fresh, GTFS-verified live buses within {@code radiusMeters} of the supplied
   * user coordinates, sorted nearest-first.  No proximity limit is applied to
   * {@link #buses(String)}; nearby filtering is only ever done here.
   *
   * @param lat user latitude (Delhi/NCR validated by the controller)
   * @param lng user longitude
   * @param radiusMeters search radius, clamped to [500, 10000] m
   * @param routeNumber optional public-route filter; blank → all routes
   */
  public NearbyResult nearbyBuses(double lat, double lng, double radiusMeters, String routeNumber) {
    double radius = Math.max(500, Math.min(10000, radiusMeters));
    String query = routeNumber == null ? "" : routeNumber.trim().toUpperCase(Locale.ROOT);
    List<NearbyBus> out = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    int unmapped = 0, stale = 0;
    for (var v : live.vehicles().values()) {
      if (!RealtimeSnapshot.fresh(v.timestamp())) { stale++; continue; }
      var r = route(v);
      if (r == null || r.number() == null) { unmapped++; continue; }
      if (!query.isEmpty() && !query.equals(r.number())) continue;
      if (live.cancelled(v.tripId())) continue;
      double dist = Geometry.distance(lat, lng, v.lat(), v.lng());
      if (dist > radius) continue;
      out.add(new NearbyBus(
          v.id(), r.id(), r.number(),
          r.direction().isEmpty() ? v.direction() : r.direction(),
          v.tripId(), v.lat(), v.lng(), v.speedKmh(), dist, v.timestamp()));
    }
    // Nearest-first, then by vehicleId for stable ordering of equidistant buses
    out.sort(Comparator.comparingDouble(NearbyBus::distanceMeters)
        .thenComparing(NearbyBus::vehicleId));
    if (unmapped > 0)
      warnings.add(unmapped + " live vehicles excluded (missing or conflicting GTFS mappings).");
    if (stale > 0) warnings.add(stale + " stale vehicle positions excluded.");
    boolean feedAvail = live.feedError == null;
    if (!feedAvail) warnings.add(live.feedError);
    if (live.fetchedAt > 0 && Instant.now().getEpochSecond() - live.fetchedAt > 120) {
      warnings.add("Realtime feed is stale or unavailable.");
      feedAvail = false;
    }
    return new NearbyResult(out, warnings, live.fetchedAt, radius, feedAvail);
  }

  public TransitIndex.Stop stop(String id) {
    var s = index.stops.get(id);
    if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown GTFS stop ID");
    return s;
  }

  public Arrivals arrivals(String stopId) {
    stop(stopId);
    List<Arrival> result = new ArrayList<>();
    int unresolved = 0;
    Set<String> serving = index.routesAtStop.getOrDefault(stopId, Set.of());
    for (var v : live.vehicles().values()) {
      var r = route(v);
      if (r == null
          || !serving.contains(r.id())
          || !RealtimeSnapshot.fresh(v.timestamp())
          || live.cancelled(v.tripId())) continue;
      var t = index.trips.get(v.tripId());
      if (t == null) {
        unresolved++;
        continue;
      }
      if (!t.direction().isBlank()
          && !v.direction().isBlank()
          && !t.direction().equals(v.direction())) continue;
      var visits = t.visits().stream().filter(s -> s.stopId().equals(stopId)).toList();
      if (visits.isEmpty()) continue;
      // Repeated stops on loops require current_stop_sequence to disambiguate the occurrence.
      if (visits.size() > 1 && v.sequence() == null) {
        unresolved++;
        continue;
      }
      var visit =
          visits.stream()
              .filter(s -> v.sequence() == null || s.sequence() >= v.sequence())
              .findFirst()
              .orElse(null);
      if (visit == null) continue;
      // Count the number of stops the bus will serve before reaching the requested stop.
      Integer remainingStops = computeRemainingStops(t, v, visit);
      Arrival a = predict(v, t, visit, r.number(), remainingStops);
      if (a != null) result.add(a);
    }
    result.sort(Comparator.comparing(Arrival::etaMinutes, Comparator.nullsLast(Double::compareTo)));
    List<String> warnings = new ArrayList<>();
    if (index.warning != null) warnings.add(index.warning);
    if (live.feedError != null) warnings.add(live.feedError);
    if (unresolved > 0)
      warnings.add(unresolved + " vehicles cannot be resolved to an unambiguous approaching trip.");
    return new Arrivals(result, warnings);
  }

  /**
   * Counts the number of scheduled stops a vehicle will serve before reaching
   * {@code targetVisit}. Returns null when the stop sequence cannot be determined.
   */
  private Integer computeRemainingStops(
      TransitIndex.Trip trip, RealtimeSnapshot.Vehicle v, TransitIndex.Visit targetVisit) {
    if (v.sequence() == null) return null;
    // Count visits in the trip between current stop sequence and the target (exclusive).
    long count = trip.visits().stream()
        .filter(vis -> vis.sequence() > v.sequence() && vis.sequence() < targetVisit.sequence())
        .count();
    return (int) count;
  }

  private Arrival predict(
      RealtimeSnapshot.Vehicle v, TransitIndex.Trip trip, TransitIndex.Visit visit, String number,
      Integer remainingStops) {
    Double distance = null;
    String reason = "Route geometry or reliable speed is unavailable.";
    var shape = index.shapes.get(trip.shapeId());
    Geometry.Projection vp = null, sp = null;
    boolean approaching = v.sequence() != null && v.sequence() <= visit.sequence();
    if (shape != null && shape.size() > 1) {
      // Do not project a looping route onto an arbitrary nearest segment.
      boolean loop =
          trip.visits().stream().map(TransitIndex.Visit::stopId).distinct().count()
              != trip.visits().size();
      if (!loop) {
        var s = stop(visit.stopId());
        vp = Geometry.project(shape, v.lat(), v.lng());
        sp = Geometry.project(shape, s.lat(), s.lng());
        if (vp.offset() <= 100 && sp.offset() <= 100) {
          if (sp.along() + 20 < vp.along()) return null;
          if (v.bearing() != null && v.speedKmh() != null && v.speedKmh() > 3) {
            double delta = Math.abs(v.bearing() - vp.bearing());
            if (Math.min(delta, 360 - delta) > 100) return null;
          }
          distance = Math.max(0, sp.along() - vp.along());
          approaching = true;
        }
      }
    }
    var predictions =
        live.predictions(trip.id()).stream()
            .filter(
                p ->
                    RealtimeSnapshot.fresh(p.timestamp())
                        && (p.vehicleId().isBlank() || p.vehicleId().equals(v.id()))
                        && (p.sequence() != null
                            ? p.sequence() == visit.sequence()
                            : p.stopId().equals(visit.stopId())))
            .toList();
    // Multiple service instances cannot safely be combined without a start_date match.
    if (predictions.size() == 1) {
      var p = predictions.get(0);
      if (p.skipped()) return null;
      if (p.unavailable())
        return approaching
            ? new Arrival(
                number,
                v.id(),
                trip.id(),
                distance,
                null,
                v.timestamp(),
                "UNAVAILABLE",
                "TripUpdates marks this stop NO_DATA.",
                remainingStops)
            : null;
      if (p.arrival() != null) {
        double minutes = (p.arrival() - Instant.now().getEpochSecond()) / 60.0;
        if (minutes < 0) return null;
        if (minutes <= 180)
          return new Arrival(
              number, v.id(), trip.id(), distance, minutes, v.timestamp(), "TRIP_UPDATE", null,
              remainingStops);
      }
    }
    if (distance != null && v.speedKmh() != null && v.speedKmh() >= 3)
      return new Arrival(
          number,
          v.id(),
          trip.id(),
          distance,
          distance / (v.speedKmh() * 1000 / 60),
          v.timestamp(),
          "GEOMETRY_ESTIMATE",
          "Estimate using recent speed; traffic and dwell time may change arrival.",
          remainingStops);
    if (!approaching) return null;
    return new Arrival(
        number, v.id(), trip.id(), distance, null, v.timestamp(), "UNAVAILABLE", reason,
        remainingStops);
  }
}
