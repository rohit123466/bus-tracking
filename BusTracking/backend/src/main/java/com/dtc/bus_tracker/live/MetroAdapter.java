package com.dtc.bus_tracker.live;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Adapter for Delhi Metro schedule and station data.
 *
 * <p><strong>Status: BLOCKED — no authoritative source available.</strong>
 *
 * <p>Delhi Metro does not currently publish an open GTFS or GTFS-RT feed that is
 * legally usable without a data-sharing agreement with DMRC. The One Delhi app
 * integrates metro data through a private backend that is not accessible to
 * third parties without authorization.
 *
 * <p>This adapter is the correct extension point: when an authoritative metro
 * data source (official GTFS static or realtime, or a licensed API) becomes
 * available, implement {@link #stations()}, {@link #lines()}, and
 * {@link #nextArrivals(String)} here without changing any DTC-bus service code.
 *
 * <p>Multimodal journey legs are assembled in {@code AlternativeService} by calling
 * this adapter; bus+metro journey results are hidden from the UI while
 * {@link #isAvailable()} returns false.
 */
@Component
public class MetroAdapter {

  /** Returns true only when an authoritative metro data source is configured. */
  public boolean isAvailable() {
    return false;
  }

  public record MetroStation(String id, String name, String line, double lat, double lng) {}

  public record MetroLine(String id, String name, List<MetroStation> stations) {}

  /**
   * Returns all known metro stations. Empty list until an authoritative source is configured.
   */
  public List<MetroStation> stations() {
    return List.of();
  }

  /**
   * Returns all metro lines. Empty list until an authoritative source is configured.
   */
  public List<MetroLine> lines() {
    return List.of();
  }

  /**
   * Returns next arrival times at a given station ID.
   * Returns an empty list when no authoritative realtime data is available.
   */
  public List<Long> nextArrivals(String stationId) {
    return List.of();
  }

  /** Human-readable explanation of why metro data is not available. */
  public String unavailableReason() {
    return "Delhi Metro (DMRC) schedule data is not available through an authoritative open feed. "
        + "Bus+Metro multimodal journey planning is blocked until a licensed data source is "
        + "configured. Existing DTC bus route planning is unaffected.";
  }
}
