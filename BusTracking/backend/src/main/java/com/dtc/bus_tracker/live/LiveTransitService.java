package com.dtc.bus_tracker.live;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LiveTransitService {
  public record Bus(
      String vehicleId,
      String routeId,
      String routeCode,
      String direction,
      String tripId,
      double busLatitude,
      double busLongitude,
      long lastUpdatedEpochSeconds) {}

  public record LiveResult(List<Bus> buses, List<String> warnings, long fetchedAt) {}

  public record Arrival(
      String routeNumber,
      String vehicleId,
      String tripId,
      Double distanceMeters,
      Double etaMinutes,
      long lastGpsUpdate,
      String predictionAvailability,
      String reason) {}

  public record Arrivals(List<Arrival> arrivals, List<String> warnings) {}

  private final TransitIndex index;
  private final RealtimeSnapshot live;

  public LiveTransitService(TransitIndex index, RealtimeSnapshot live) {
    this.index = index;
    this.live = live;
  }

  public TransitIndex.Route route(RealtimeSnapshot.Vehicle v) {
    var trip = index.trips.get(v.tripId());
    if (trip != null && !v.routeId().isBlank() && !trip.routeId().equals(v.routeId())) return null;
    return index.routes.get(trip != null ? trip.routeId() : v.routeId());
  }

  public LiveResult buses(String number) {
    String query = number == null ? "" : number.trim().toUpperCase(Locale.ROOT);
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
      if (!query.isEmpty() && !query.equals(r.number())) continue;
      if (live.cancelled(v.tripId())) continue;
      inferred |= r.inferred();
      out.add(
          new Bus(
              v.id(),
              r.id(),
              r.number(),
              r.direction().isEmpty() ? v.direction() : r.direction(),
              v.tripId(),
              v.lat(),
              v.lng(),
              v.timestamp()));
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
    if (!query.isEmpty() && index.routes.values().stream().noneMatch(r -> query.equals(r.number())))
      warnings.add(
          "No GTFS public-route mapping for " + query + ". Unrelated vehicles are not shown.");
    if (live.feedError != null) warnings.add(live.feedError);
    if (live.fetchedAt > 0 && Instant.now().getEpochSecond() - live.fetchedAt > 120)
      warnings.add("Realtime feed is stale or unavailable.");
    return new LiveResult(out, warnings, live.fetchedAt);
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
      Arrival a = predict(v, t, visit, r.number());
      if (a != null) result.add(a);
    }
    result.sort(Comparator.comparing(Arrival::etaMinutes, Comparator.nullsLast(Double::compareTo)));
    List<String> warnings = new ArrayList<>();
    if (index.warning != null) warnings.add(index.warning);
    if (unresolved > 0)
      warnings.add(unresolved + " vehicles cannot be resolved to an unambiguous approaching trip.");
    return new Arrivals(result, warnings);
  }

  private Arrival predict(
      RealtimeSnapshot.Vehicle v, TransitIndex.Trip trip, TransitIndex.Visit visit, String number) {
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
                "TripUpdates marks this stop NO_DATA.")
            : null;
      if (p.arrival() != null) {
        double minutes = (p.arrival() - Instant.now().getEpochSecond()) / 60.0;
        if (minutes < 0) return null;
        if (minutes <= 180)
          return new Arrival(
              number, v.id(), trip.id(), distance, minutes, v.timestamp(), "TRIP_UPDATE", null);
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
          "Estimate using recent speed; traffic and dwell time may change arrival.");
    if (!approaching) return null;
    return new Arrival(
        number, v.id(), trip.id(), distance, null, v.timestamp(), "UNAVAILABLE", reason);
  }
}
