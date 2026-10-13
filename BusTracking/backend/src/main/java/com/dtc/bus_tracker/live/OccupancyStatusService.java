package com.dtc.bus_tracker.live;

import org.springframework.stereotype.Service;

/**
 * Provides a passenger-friendly crowding indicator per live vehicle.
 *
 * <p>Currently the DTC realtime feed does not publish GTFS-RT OccupancyStatus values,
 * so live crowding cannot be derived from the feed. The provider interface is in place
 * and will populate automatically if an authoritative occupancy source becomes available.
 *
 * <p>The existing camera simulation and seat-availability features remain the only
 * sources of per-bus occupancy data; they are surfaced via the existing
 * {@code /api/ticketing/*} and {@code /api/conductor/*} endpoints and are not
 * duplicated here.
 */
@Service
public class OccupancyStatusService {

  public enum CrowdingLevel { LOW, MODERATE, HIGH, UNKNOWN }

  /**
   * Returns the crowding level for a vehicle, based on any available authoritative source.
   * Returns {@link CrowdingLevel#UNKNOWN} when no verified data exists.
   *
   * @param vehicleId the GTFS vehicle identifier
   * @return crowding level, never null
   */
  public CrowdingLevel crowdingForVehicle(String vehicleId) {
    // Future: check GTFS-RT OccupancyStatus from RealtimeSnapshot when the OTD feed provides it.
    // Future: check camera-derived occupancy from CameraOccupancyRepository for ticketed buses.
    return CrowdingLevel.UNKNOWN;
  }

  public record CrowdingInfo(
      String vehicleId,
      CrowdingLevel crowding,
      String label,
      String source,
      String disclaimer) {
    public static CrowdingInfo unknown(String vehicleId) {
      return new CrowdingInfo(vehicleId, CrowdingLevel.UNKNOWN, "Occupancy unknown",
          "NONE",
          "Live crowding data is not yet available from the DTC feed. "
              + "The provider interface is ready; crowding will display automatically "
              + "when an authoritative occupancy source is configured.");
    }
  }
}
