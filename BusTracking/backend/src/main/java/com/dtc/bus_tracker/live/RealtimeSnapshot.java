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
      String startDate,
      String startTime,
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
      String startTime,
      String routeId,
      String direction,
      String stopId,
      Integer sequence,
      Long arrival,
      long timestamp,
      boolean skipped,
      boolean unavailable) {}

  private volatile Map<String, Vehicle> vehicles = Map.of();
  private volatile Map<String, List<Prediction>> predictions = Map.of();
  private record Cancellation(TripDescriptor trip, long timestamp) {}
  private volatile List<Cancellation> cancelled = List.of();
  public volatile long fetchedAt;
  public volatile String feedError = "Live feed has not been received.";
  public volatile String tripUpdatesError;

  public Map<String, Vehicle> vehicles() {
    return vehicles;
  }

  public List<Prediction> predictions(String tripId) {
    return predictions.getOrDefault(tripId, List.of());
  }

  public boolean cancelled(Vehicle vehicle) {
    return cancelled.stream().anyMatch(c -> fresh(c.timestamp())
        && c.trip().getTripId().equals(vehicle.tripId())
        && (c.trip().getRouteId().isBlank() || c.trip().getRouteId().equals(vehicle.routeId()))
        && (!c.trip().hasDirectionId() || String.valueOf(c.trip().getDirectionId()).equals(vehicle.direction()))
        && sameInstance(c.trip().getStartDate(), c.trip().getStartTime(), vehicle));
  }

  public static boolean sameInstance(String date, String time, Vehicle vehicle) {
    return date.equals(vehicle.startDate()) && time.equals(vehicle.startTime());
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
    List<Cancellation> cancelledTrips = new ArrayList<>();
    long header = feed.getHeader().hasTimestamp() ? feed.getHeader().getTimestamp() : 0;
    boolean hasUpdates = false;
    for (var entity : feed.getEntityList()) {
      if (entity.getIsDeleted()) continue;
      if (entity.hasVehicle()) {
        var v = entity.getVehicle();
        var td = v.getTrip();
        if (td.getScheduleRelationship() != TripDescriptor.ScheduleRelationship.SCHEDULED) continue;
        var p = v.getPosition();
        String id = v.getVehicle().getId();
        if (id.isBlank()) id = v.getVehicle().getLabel();
        if (id.isBlank() || !v.hasPosition() || !p.hasLatitude() || !p.hasLongitude()) continue;
        double lat = p.getLatitude(), lng = p.getLongitude();
        if (!Double.isFinite(lat)
            || !Double.isFinite(lng)
            || Math.abs(lat) > 90
            || Math.abs(lng) > 180) continue;
        // Feed publication time is not the time the GPS fix was measured.
        long stamp = v.hasTimestamp() ? v.getTimestamp() : 0;
        Double speed = p.hasSpeed() ? p.getSpeed() * 3.6 : null;
        var old = vehicles.get(id);
        if (speed == null
            && old != null
            && stamp - old.timestamp() >= 5
            && stamp - old.timestamp() <= 120
            && !td.getTripId().isBlank()
            && old.tripId().equals(td.getTripId())
            && old.routeId().equals(td.getRouteId())
            && old.startDate().equals(td.getStartDate())
            && old.startTime().equals(td.getStartTime())) {
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
                td.getStartDate(),
                td.getStartTime(),
                td.hasDirectionId() ? String.valueOf(td.getDirectionId()) : "",
                v.hasCurrentStopSequence() ? v.getCurrentStopSequence() : null,
                lat,
                lng,
                speed,
                p.hasBearing() && Float.isFinite(p.getBearing()) && p.getBearing() >= 0
                    && p.getBearing() < 360 ? (double) p.getBearing() : null,
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
          cancelledTrips.add(new Cancellation(u.getTrip(), stamp));
          continue;
        }
        if (u.getTrip().getScheduleRelationship() != TripDescriptor.ScheduleRelationship.SCHEDULED)
          continue;
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
                      u.getTrip().getStartTime(),
                      u.getTrip().getRouteId(),
                      u.getTrip().hasDirectionId() ? String.valueOf(u.getTrip().getDirectionId()) : "",
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
    if (updateVehicles) {
      vehicles = Map.copyOf(next);
      fetchedAt = Instant.now().getEpochSecond();
      feedError = !next.isEmpty() && next.values().stream().noneMatch(v -> fresh(v.timestamp()))
          ? "DTC feed contains no fresh vehicle positions." : null;
    }
    if (updateTrips || hasUpdates) {
      predictions = Map.copyOf(times);
      cancelled = List.copyOf(cancelledTrips);
      tripUpdatesError = null;
    }
  }
}
