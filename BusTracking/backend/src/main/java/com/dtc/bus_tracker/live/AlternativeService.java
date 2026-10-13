package com.dtc.bus_tracker.live;

import java.util.*;
import org.springframework.stereotype.Service;

/** Direct and one-transfer search using observed predictions, never invented headways. */
@Service
public class AlternativeService {
  public record Leg(
      String routeNumber,
      String vehicleId,
      String boardStopId,
      String alightStopId,
      double waitMinutes,
      double rideMinutes,
      String boardStopName,
      String alightStopName) {}

  public record Option(
      double totalMinutes,
      double walkMinutes,
      double waitMinutes,
      double rideMinutes,
      double transferMinutes,
      double walkingDistanceMeters,
      List<Leg> legs) {}

  public record Result(
      String status, String message, Double baselineMinutes, List<Option> options) {}

  private final TransitIndex index;
  private final LiveTransitService transit;
  private final WalkingService walking;

  public AlternativeService(
      TransitIndex index, LiveTransitService transit, WalkingService walking) {
    this.index = index;
    this.transit = transit;
    this.walking = walking;
  }

  public Result alternatives(String source, String destination, String routeNumber) {
    var origin = transit.stop(source);
    if (destination == null || destination.isBlank())
      return new Result(
          "DESTINATION_REQUIRED",
          "Choose a destination stop to compare complete journeys.",
          null,
          List.of());
    transit.stop(destination);
    if (source.equals(destination))
      return new Result("AT_DESTINATION", "You are already at your destination.", 0.0, List.of());
    Map<String, List<LiveTransitService.Arrival>> cache = new HashMap<>();
    var initial =
        at(source, cache).stream()
            .filter(
                a ->
                    routeNumber == null
                        || routeNumber.isBlank()
                        || routeNumber.equalsIgnoreCase(a.routeNumber()))
            .toList();
    var baselineOptions = journeys(source, destination, 0, 0, initial, cache);
    var baseline =
        baselineOptions.stream().min(Comparator.comparingDouble(Option::totalMinutes)).orElse(null);
    if (baseline == null)
      return new Result(
          "PREDICTION_UNAVAILABLE",
          "No complete, reliably timed journey to this destination is available for comparison.",
          null,
          List.of());
    if (baselineOptions.stream().mapToDouble(o -> o.legs().get(0).waitMinutes()).min().orElse(0)
        <= 15)
      return new Result(
          "WAIT_WITHIN_THRESHOLD",
          "A suitable bus is predicted within 15 minutes.",
          baseline.totalMinutes(),
          List.of());
    List<Option> options = new ArrayList<>();
    List<TransitIndex.Stop> candidates = new ArrayList<>();
    candidates.add(origin);
    index.nearby(origin.lat(), origin.lng(), 800).stream()
        .filter(s -> !s.id().equals(source))
        .limit(4)
        .forEach(candidates::add);
    for (var board : candidates) {
      double walk = 0;
      double walkingDistance = 0;
      if (!board.id().equals(source)) {
        var path = walking.route(origin.lat(), origin.lng(), board);
        if (!path.available()) continue;
        walk = path.durationSeconds() / 60;
        walkingDistance = path.distanceMeters();
      }
      options.addAll(journeys(board.id(), destination, walk, walkingDistance, at(board.id(), cache), cache));
    }
    double threshold = Math.max(3, baseline.totalMinutes() * .1);
    var better =
        options.stream()
            .filter(o -> o.totalMinutes() <= baseline.totalMinutes() - threshold)
            .sorted(Comparator.comparingDouble(Option::totalMinutes))
            .limit(3)
            .toList();
    return new Result(
        better.isEmpty() ? "NO_BETTER_OPTION" : "ALTERNATIVES",
        better.isEmpty()
            ? "No verified alternative improves total journey time by at least 3 minutes and 10%."
            : "Faster predicted journeys including walking and transfer waits; predictions may"
                  + " change.",
        baseline.totalMinutes(),
        better);
  }

  private List<LiveTransitService.Arrival> at(
      String stop, Map<String, List<LiveTransitService.Arrival>> cache) {
    return cache.computeIfAbsent(
        stop,
        k -> transit.arrivals(k).arrivals().stream().filter(a -> a.etaMinutes() != null).toList());
  }

  private List<Option> journeys(
      String source,
      String dest,
      double walk,
      double walkingDistance,
      List<LiveTransitService.Arrival> first,
      Map<String, List<LiveTransitService.Arrival>> cache) {
    List<Option> out = new ArrayList<>();
    for (var a : first) {
      if (a.etaMinutes() < walk + .5) continue;
      var trip = index.trips.get(a.tripId());
      if (trip == null) continue;
      int from = position(trip, source);
      if (from < 0) continue;
      var direct = arrivalFor(a, dest, cache);
      if (position(trip, dest) > from && direct != null && direct.etaMinutes() > a.etaMinutes()) {
        var leg =
            new Leg(
                a.routeNumber(),
                a.vehicleId(),
                source,
                dest,
                a.etaMinutes() - walk,
                direct.etaMinutes() - a.etaMinutes(), transit.stop(source).name(), transit.stop(dest).name());
        out.add(
            new Option(
                direct.etaMinutes(), walk, leg.waitMinutes(), leg.rideMinutes(), 0, walkingDistance, List.of(leg)));
      }
      for (var visit : trip.visits().subList(from + 1, trip.visits().size())) {
        String transfer = visit.stopId();
        if (transfer.equals(dest) || index.routesAtStop.getOrDefault(transfer, Set.of()).size() < 2)
          continue;
        var reached = arrivalFor(a, transfer, cache);
        if (reached == null || reached.etaMinutes() <= a.etaMinutes()) continue;
        for (var b : at(transfer, cache)) {
          if (b.vehicleId().equals(a.vehicleId()) || b.etaMinutes() < reached.etaMinutes() + 2)
            continue;
          var second = index.trips.get(b.tripId());
          if (second == null || position(second, dest) <= position(second, transfer)) continue;
          var end = arrivalFor(b, dest, cache);
          if (end == null || end.etaMinutes() <= b.etaMinutes()) continue;
          var l1 =
              new Leg(
                  a.routeNumber(),
                  a.vehicleId(),
                  source,
                  transfer,
                  a.etaMinutes() - walk,
                  reached.etaMinutes() - a.etaMinutes(), transit.stop(source).name(), transit.stop(transfer).name());
          var l2 =
              new Leg(
                  b.routeNumber(),
                  b.vehicleId(),
                  transfer,
                  dest,
                  b.etaMinutes() - reached.etaMinutes() - 2,
                  end.etaMinutes() - b.etaMinutes(), transit.stop(transfer).name(), transit.stop(dest).name());
          out.add(
              new Option(
                  end.etaMinutes(),
                  walk,
                  l1.waitMinutes() + l2.waitMinutes(),
                  l1.rideMinutes() + l2.rideMinutes(),
                  2,
                  walkingDistance,
                  List.of(l1, l2)));
        }
      }
    }
    return out;
  }

  private LiveTransitService.Arrival arrivalFor(
      LiveTransitService.Arrival bus,
      String stop,
      Map<String, List<LiveTransitService.Arrival>> cache) {
    return at(stop, cache).stream()
        .filter(a -> a.vehicleId().equals(bus.vehicleId()) && a.tripId().equals(bus.tripId()))
        .findFirst()
        .orElse(null);
  }

  private int position(TransitIndex.Trip t, String stop) {
    int found = -1;
    for (int i = 0; i < t.visits().size(); i++)
      if (t.visits().get(i).stopId().equals(stop)) {
        if (found >= 0) return -1;
        found = i;
      }
    return found;
  }
}
