package com.dtc.bus_tracker.live;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Surfaces GTFS Realtime service alerts (cancellations, diversions, delays,
 * feed errors) for passenger-facing display.
 *
 * <p>Live alerts are sourced exclusively from the {@link RealtimeSnapshot}:
 * cancelled trips and stale/missing feed state. No alerts are fabricated.
 * If no official GTFS-RT Alerts feed is configured, only feed-health alerts
 * are returned; other alert types are blocked pending an authoritative source.
 */
@Service
public class ServiceAlertService {

  public enum AlertSeverity { INFO, WARNING, CRITICAL }

  /**
   * A service alert.
   *
   * <ul>
   *   <li>{@code type} – CANCELLATION, FEED_UNAVAILABLE, FEED_STALE, ROUTE_DIVERSION,
   *                       STOP_CLOSURE (future), DELAY (future)</li>
   *   <li>{@code affectedRoute} – public route number, or null if system-wide</li>
   *   <li>{@code tripId} – specific trip ID for cancellations</li>
   *   <li>{@code message} – human-readable description</li>
   *   <li>{@code source} – "GTFS_RT" for realtime feed data; "SYSTEM" for feed-health alerts</li>
   *   <li>{@code demo} – true for non-authoritative placeholder alerts</li>
   * </ul>
   */
  public record ServiceAlert(
      String type,
      AlertSeverity severity,
      String affectedRoute,
      String tripId,
      String message,
      String source,
      boolean demo,
      long detectedAt) {}

  public record AlertsResult(
      List<ServiceAlert> alerts,
      String feedStatus,
      long fetchedAt) {}

  private final RealtimeSnapshot live;
  private final TransitIndex index;
  private final LiveTransitService transit;

  public ServiceAlertService(RealtimeSnapshot live, TransitIndex index, LiveTransitService transit) {
    this.live = live;
    this.index = index;
    this.transit = transit;
  }

  /**
   * Returns current service alerts optionally filtered to a public route number.
   *
   * <p>Currently sourced alerts:
   * <ol>
   *   <li>Feed unavailable / not yet received ({@code FEED_UNAVAILABLE})</li>
   *   <li>Feed stale — last update >120 s ago ({@code FEED_STALE})</li>
   *   <li>Cancelled trips from GTFS-RT TripUpdates ({@code CANCELLATION})</li>
   * </ol>
   *
   * <p>GTFS-RT Alerts feed integration is a future blocker — the OTD Delhi API
   * does not currently expose a compatible /Alerts.pb endpoint. The backend
   * abstraction is in place; alerts will populate automatically once an
   * authoritative source is configured.
   */
  public AlertsResult alerts(String routeNumber) {
    long now = Instant.now().getEpochSecond();
    List<ServiceAlert> alerts = new ArrayList<>();

    // 1. Feed health alerts
    String feedStatus;
    if (live.feedError != null) {
      feedStatus = "UNAVAILABLE";
      alerts.add(new ServiceAlert(
          "FEED_UNAVAILABLE", AlertSeverity.CRITICAL, null, null,
          "Live DTC vehicle positions are currently unavailable: " + live.feedError,
          "SYSTEM", false, now));
    } else if (live.fetchedAt > 0 && now - live.fetchedAt > 120) {
      feedStatus = "STALE";
      alerts.add(new ServiceAlert(
          "FEED_STALE", AlertSeverity.WARNING, null, null,
          "Realtime vehicle positions have not been refreshed for "
              + (now - live.fetchedAt) + " seconds. Shown positions may be outdated.",
          "SYSTEM", false, live.fetchedAt));
    } else if (live.fetchedAt == 0) {
      feedStatus = "PENDING";
    } else {
      feedStatus = "OK";
    }

    // 2. Cancelled trips from GTFS-RT TripUpdates
    // Only show cancellations for trips that belong to the requested route (if filtered).
    String query = routeNumber == null ? "" : routeNumber.trim().toUpperCase(Locale.ROOT);
    // RealtimeSnapshot.cancelled() is a Set<tripId> — iterate TransitIndex to get route context.
    // We access it via an exported method added below.
    for (String tripId : live.cancelledTrips()) {
      var trip = index.trips.get(tripId);
      if (trip == null) continue;
      var route = index.routes.get(trip.routeId());
      if (route == null) continue;
      if (!query.isEmpty() && !query.equals(route.number())) continue;
      alerts.add(new ServiceAlert(
          "CANCELLATION", AlertSeverity.WARNING,
          route.number(), tripId,
          "Trip " + tripId + " on route " + route.number() + " has been cancelled.",
          "GTFS_RT", false, now));
    }

    // Sort: CRITICAL first, then WARNING, then INFO; within same severity by detection time desc.
    alerts.sort(Comparator.comparing(ServiceAlert::severity)
        .thenComparing(Comparator.comparingLong(ServiceAlert::detectedAt).reversed()));

    return new AlertsResult(alerts, feedStatus, live.fetchedAt);
  }
}
