package com.dtc.bus_tracker.live;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RealtimeSnapshot {
  public record Vehicle(
      String id,
      String routeId,
      String tripId,
      String direction,
      Integer sequence,
      double lat,
      double lng,
      Double speedKmh,
      Double bearing,
      long timestamp) {}

  public record Prediction(
      String tripId,
      String vehicleId,
      String startDate,
      String stopId,
      Integer sequence,
      Long arrival,
      long timestamp,
      boolean skipped,
      boolean unavailable) {}

  private volatile Map<String, Vehicle> vehicles = Map.of();
  private volatile Map<String, List<Prediction>> predictions = Map.of();
  private volatile Set<String> cancelled = Set.of();
  public volatile long fetchedAt;
  public volatile String feedError = "Live feed has not been received.";
  /** Most recent fetch failure and when (epoch seconds); unlike feedError, kept after recovery. */
  public volatile String lastError;
  public volatile long lastErrorAt;
  /** Incremented every time positions or trip updates are replaced, so derived views rebuild. */
  private volatile long generation;
  private final List<Runnable> vehicleListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  public Map<String, Vehicle> vehicles() {
    return vehicles;
  }

  public long generation() {
    return generation;
  }

  /** Runs on the feed thread right after each vehicle update; keep it quick or hand off. */
  public void onVehiclesUpdated(Runnable listener) {
    vehicleListeners.add(listener);
  }

  public List<Prediction> predictions(String tripId) {
    return predictions.getOrDefault(tripId, List.of());
  }

  public boolean cancelled(String tripId) {
    return cancelled.contains(tripId);
  }

  /** Returns the current set of cancelled trip IDs from the latest TripUpdates feed. */
  public Set<String> cancelledTrips() {
    return cancelled;
  }

  /**
   * The feed was polled and has not changed (HTTP 304). Same outcome as accepting the identical
   * feed again: the fetch time moves on and the "no fresh positions" check is redone.
   */
  public synchronized void unchanged() {
    fetchedAt = Instant.now().getEpochSecond();
    String error = noFreshVehicles(vehicles.values()) ? NO_FRESH_VEHICLES : null;
    if (!Objects.equals(error, feedError)) {
      feedError = error;
      generation++;
    }
  }

  /** A poll failed: report it as the current feed problem and remember it for the admin console. */
  public void recordFailure(String message) {
    feedError = message;
    lastError = message;
    lastErrorAt = Instant.now().getEpochSecond();
  }

  private static final String NO_FRESH_VEHICLES = "DTC feed contains no fresh vehicle positions.";

  private static boolean noFreshVehicles(Collection<Vehicle> list) {
    return !list.isEmpty() && list.stream().noneMatch(v -> fresh(v.timestamp()));
  }

  public static boolean fresh(long stamp) {
    long age = Instant.now().getEpochSecond() - stamp;
    return stamp > 0 && age >= -30 && age <= 120;
  }

  public synchronized void accept(FeedMessage feed, boolean updateVehicles, boolean updateTrips) {
    // Differential feeds need entity deletion/state reconciliation; fail closed instead of mixing
    // snapshots.
    if (feed.getHeader().getIncrementality() == FeedHeader.Incrementality.DIFFERENTIAL)
      throw new IllegalArgumentException("Differential GTFS realtime feeds are not supported.");
    Map<String, Vehicle> next = new HashMap<>();
    Map<String, List<Prediction>> times = new HashMap<>();
    Set<String> cancelledTrips = new HashSet<>();
    long header = feed.getHeader().hasTimestamp() ? feed.getHeader().getTimestamp() : 0;
    boolean hasUpdates = false;
    for (var entity : feed.getEntityList()) {
      if (entity.getIsDeleted()) continue;
      if (entity.hasVehicle()) {
        var v = entity.getVehicle();
        var td = v.getTrip();
        var p = v.getPosition();
        String id = v.getVehicle().getId();
        if (id.isBlank()) id = v.getVehicle().getLabel();
        if (id.isBlank() || !v.hasPosition() || !p.hasLatitude() || !p.hasLongitude()) continue;
        double lat = p.getLatitude(), lng = p.getLongitude();
        if (!Double.isFinite(lat)
            || !Double.isFinite(lng)
            || Math.abs(lat) > 90
            || Math.abs(lng) > 180) continue;
        long stamp = v.hasTimestamp() ? v.getTimestamp() : header;
        Double speed = p.hasSpeed() ? p.getSpeed() * 3.6 : null;
        var old = vehicles.get(id);
        if ((speed == null || speed == 0)
            && old != null
            && stamp - old.timestamp() >= 5
            && stamp - old.timestamp() <= 120
            && old.tripId().equals(td.getTripId())) {
          double derived =
              Geometry.distance(lat, lng, old.lat(), old.lng()) / (stamp - old.timestamp()) * 3.6;
          if (derived >= 3 && derived <= 100) speed = derived;
        }
        if (speed != null && (!Double.isFinite(speed) || speed < 0 || speed > 120)) speed = null;
        Vehicle candidate =
            new Vehicle(
                id,
                td.getRouteId(),
                td.getTripId(),
                td.hasDirectionId() ? String.valueOf(td.getDirectionId()) : "",
                v.hasCurrentStopSequence() ? v.getCurrentStopSequence() : null,
                lat,
                lng,
                speed,
                p.hasBearing() ? (double) p.getBearing() : null,
                stamp);
        if (!next.containsKey(id) || next.get(id).timestamp() < stamp) next.put(id, candidate);
      }
      if (entity.hasTripUpdate()) {
        hasUpdates = true;
        var u = entity.getTripUpdate();
        String trip = u.getTrip().getTripId();
        if (trip.isBlank()) continue;
        long stamp = u.hasTimestamp() ? u.getTimestamp() : header;
        if (!fresh(stamp)) continue;
        if (u.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED) {
          cancelledTrips.add(trip);
          continue;
        }
        for (var s : u.getStopTimeUpdateList()) {
          Long arrival =
              s.hasArrival() && s.getArrival().hasTime() ? s.getArrival().getTime() : null;
          boolean unavailable =
              s.getScheduleRelationship() == TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA;
          times
              .computeIfAbsent(trip, k -> new ArrayList<>())
              .add(
                  new Prediction(
                      trip,
                      u.getVehicle().getId(),
                      u.getTrip().getStartDate(),
                      s.getStopId(),
                      s.hasStopSequence() ? s.getStopSequence() : null,
                      arrival,
                      stamp,
                      s.getScheduleRelationship()
                          == TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED,
                      unavailable));
        }
      }
    }
    if (updateTrips || hasUpdates) {
      predictions = Map.copyOf(times);
      cancelled = Set.copyOf(cancelledTrips);
      generation++;
    }
    if (updateVehicles) {
      vehicles = Map.copyOf(next);
      fetchedAt = Instant.now().getEpochSecond();
      feedError = noFreshVehicles(next.values()) ? NO_FRESH_VEHICLES : null;
      generation++;
      for (var listener : vehicleListeners) listener.run();
    }
  }
}
