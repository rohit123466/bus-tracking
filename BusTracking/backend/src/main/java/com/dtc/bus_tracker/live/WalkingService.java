package com.dtc.bus_tracker.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WalkingService {
  public record Maneuver(String instruction, double distance, int fromIndex, int toIndex) {}

  public record Walk(
      TransitIndex.Stop stop,
      boolean available,
      Double distanceMeters,
      Double durationSeconds,
      List<List<Double>> coordinates,
      List<Maneuver> maneuvers,
      String message) {}

  private final TransitIndex index;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${walking.ors-key:}")
  private String key = "";

  @Value("${walking.ors-url:https://api.openrouteservice.org/v2/directions/foot-walking/geojson}")
  private String url;

  // No GPS history persisted; only a bounded 30-second request coalescing cache in memory.
  private record Cached(long at, Walk walk) {}

  private final Map<String, Cached> cache = new LinkedHashMap<>();
  private long retryAfter;

  public WalkingService(TransitIndex index) {
    this.index = index;
  }

  public synchronized Walk route(double lat, double lng, TransitIndex.Stop stop) {
    String cacheKey = Math.round(lat * 100000) + ":" + Math.round(lng * 100000) + ":" + stop.id();
    long now = System.currentTimeMillis();
    cache.entrySet().removeIf(e -> now - e.getValue().at() > 30000);
    if (cache.containsKey(cacheKey)) return cache.get(cacheKey).walk();
    if (key.isBlank() || now < retryAfter)
      return fallback(
          lat,
          lng,
          stop,
          "Pedestrian routing unavailable; straight-line distance only. Reachability is unknown.");
    try {
      if (!URI.create(url).getPath().contains("/foot-walking/"))
        throw new IllegalArgumentException("A foot-walking endpoint is required");
      String body =
          json.writeValueAsString(
              Map.of(
                  "coordinates",
                  List.of(List.of(lng, lat), List.of(stop.lng(), stop.lat())),
                  "instructions",
                  true));
      var req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(8))
              .header("Authorization", key)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        if (response.statusCode() == 429 || response.statusCode() >= 500) retryAfter = now + 30000;
        return fallback(
            lat,
            lng,
            stop,
            "No pedestrian route returned; straight-line distance only. Reachability is unknown.");
      }
      var feature = json.readTree(response.body()).path("features").path(0);
      var summary = feature.path("properties").path("summary");
      List<List<Double>> coordinates = new ArrayList<>();
      for (var p : feature.path("geometry").path("coordinates")) {
        if (!p.isArray() || p.size() < 2 || !p.get(0).isNumber() || !p.get(1).isNumber()
            || !Double.isFinite(p.get(0).asDouble()) || !Double.isFinite(p.get(1).asDouble())
            || Math.abs(p.get(0).asDouble()) > 180 || Math.abs(p.get(1).asDouble()) > 90)
          throw new IllegalArgumentException("Invalid pedestrian geometry");
        coordinates.add(List.of(p.get(1).asDouble(), p.get(0).asDouble()));
      }
      if (coordinates.size() < 2 || !summary.has("distance") || !summary.has("duration"))
        throw new IllegalArgumentException("Incomplete pedestrian route");
      var start = coordinates.getFirst();
      var end = coordinates.getLast();
      if (Geometry.distance(lat, lng, start.get(0), start.get(1)) > 75
          || Geometry.distance(stop.lat(), stop.lng(), end.get(0), end.get(1)) > 75)
        throw new IllegalArgumentException(
            "Provider snapped endpoints too far from the requested locations");
      if (!summary.path("distance").isNumber() || !summary.path("duration").isNumber()
          || !Double.isFinite(summary.path("distance").asDouble())
          || !Double.isFinite(summary.path("duration").asDouble())
          || summary.path("distance").asDouble() < 0 || summary.path("duration").asDouble() < 0
          || (summary.path("distance").asDouble() > 1 && summary.path("duration").asDouble() == 0))
        throw new IllegalArgumentException("Invalid route metrics");
      List<Maneuver> maneuvers = new ArrayList<>();
      for (var segment : feature.path("properties").path("segments"))
        for (var step : segment.path("steps")) {
          int from = step.path("way_points").path(0).asInt(-1),
              to = step.path("way_points").path(1).asInt(-1);
          if (from >= 0 && to >= from && to < coordinates.size())
            maneuvers.add(
                new Maneuver(
                    step.path("instruction").asText(), step.path("distance").asDouble(), from, to));
        }
      Walk walk =
          new Walk(
              stop,
              true,
              summary.path("distance").asDouble(),
              summary.path("duration").asDouble(),
              coordinates,
              maneuvers,
              null);
      if (cache.size() >= 100) cache.remove(cache.keySet().iterator().next());
      cache.put(cacheKey, new Cached(now, walk));
      return walk;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return fallback(lat, lng, stop, "Routing interrupted; straight-line distance only.");
    } catch (Exception e) {
      retryAfter = now + 30000;
      return fallback(
          lat,
          lng,
          stop,
          "Pedestrian routing failed; straight-line distance only. Turn-by-turn directions"
              + " disabled.");
    }
  }

  public Walk nearest(double lat, double lng) {
    var candidates = index.nearby(lat, lng, 2000);
    if (candidates.isEmpty())
      return new Walk(
          null, false, null, null, List.of(), List.of(), "No GTFS stops found within 2 km.");
    if (key.isBlank())
      return fallback(
          lat,
          lng,
          candidates.get(0),
          "Nearest by straight-line distance only; configure pedestrian routing to check"
              + " reachability.");
    Walk best = null;
    int checked = 0;
    boolean incomplete = false;
    for (var stop : candidates) {
      double lower = Geometry.distance(lat, lng, stop.lat(), stop.lng());
      if (best != null && lower >= best.distanceMeters()) break;
      if (checked++ >= 8) {
        incomplete = true;
        break;
      }
      Walk route = route(lat, lng, stop);
      if (route.available() && (best == null || route.distanceMeters() < best.distanceMeters()))
        best = route;
      else if (!route.available()) incomplete = true;
    }
    if (best == null)
      return fallback(
          lat,
          lng,
          candidates.get(0),
          "No reachable pedestrian route could be verified; straight-line distance only.");
    return new Walk(
        best.stop(),
        true,
        best.distanceMeters(),
        best.durationSeconds(),
        best.coordinates(),
        best.maneuvers(),
        incomplete
            ? "Closest verified walking route among checked stops; some candidates could not be"
                  + " evaluated."
            : "Nearest reachable stop within the 2 km search radius.");
  }

  private Walk fallback(double lat, double lng, TransitIndex.Stop s, String message) {
    return new Walk(
        s,
        false,
        Geometry.distance(lat, lng, s.lat(), s.lng()),
        null,
        List.of(),
        List.of(),
        message);
  }
}
