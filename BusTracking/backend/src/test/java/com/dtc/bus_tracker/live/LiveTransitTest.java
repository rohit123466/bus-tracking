package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class LiveTransitTest {
  TransitIndex index;
  RealtimeSnapshot snapshot;
  LiveTransitService service;
  long now;

  @BeforeEach
  void setup() {
    now = Instant.now().getEpochSecond();
    index = new TransitIndex();
    snapshot = new RealtimeSnapshot();
    service = new LiveTransitService(index, snapshot);
    index.routes.put("1824", new TransitIndex.Route("1824", "534", "534UP", "UP", true));
    index.routes.put("534", new TransitIndex.Route("534", "221", "221UP", "UP", true));
    index.stops.put("s", new TransitIndex.Stop("s", "Source", 28.601, 77.2));
    index.stops.put("d", new TransitIndex.Stop("d", "Destination", 28.602, 77.2));
    index.trips.put(
        "t",
        new TransitIndex.Trip(
            "t",
            "1824",
            "0",
            "shape",
            List.of(
                new TransitIndex.Visit("s", 1, 36000, 36000),
                new TransitIndex.Visit("d", 2, 36600, 36600))));
    index.routesAtStop.put("s", Set.of("1824"));
    index.routesAtStop.put("d", Set.of("1824"));
  }

  FeedEntity vehicle(String id, String route, String trip, long stamp, double lat) {
    return FeedEntity.newBuilder()
        .setId(id)
        .setVehicle(
            VehiclePosition.newBuilder()
                .setVehicle(VehicleDescriptor.newBuilder().setId(id))
                .setTrip(
                    TripDescriptor.newBuilder().setRouteId(route).setTripId(trip).setDirectionId(0))
                .setPosition(
                    Position.newBuilder().setLatitude((float) lat).setLongitude(77.2f).setSpeed(5))
                .setTimestamp(stamp))
        .build();
  }

  FeedEntity update(String id, int sourceMinutes, int destinationMinutes) {
    return FeedEntity.newBuilder()
        .setId("u" + id)
        .setTripUpdate(
            TripUpdate.newBuilder()
                .setTrip(TripDescriptor.newBuilder().setTripId("t"))
                .setVehicle(VehicleDescriptor.newBuilder().setId(id))
                .setTimestamp(now)
                .addStopTimeUpdate(
                    TripUpdate.StopTimeUpdate.newBuilder()
                        .setStopId("s")
                        .setStopSequence(1)
                        .setArrival(
                            TripUpdate.StopTimeEvent.newBuilder()
                                .setTime(now + sourceMinutes * 60)))
                .addStopTimeUpdate(
                    TripUpdate.StopTimeUpdate.newBuilder()
                        .setStopId("d")
                        .setStopSequence(2)
                        .setArrival(
                            TripUpdate.StopTimeEvent.newBuilder()
                                .setTime(now + destinationMinutes * 60))))
        .build();
  }

  void feed(FeedEntity... entities) {
    snapshot.accept(
        FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now))
            .addAllEntity(List.of(entities))
            .build(),
        true,
        true);
  }

  @Test
  void publicNameFallbackPreservesVariants() {
    assertEquals("534", TransitIndex.publicNumber("", "534DOWN"));
    assertEquals("534E", TransitIndex.publicNumber("", "534EUP"));
    assertNull(TransitIndex.publicNumber("", "Some bus route"));
    assertEquals("534", TransitIndex.publicNumber("534", "anything"));
  }

  @Test
  void exactPublicNumberNeverMatchesInternalRouteId() {
    feed(
        vehicle("a", "1824", "t", now, 28.6),
        vehicle("b", "1824", "t", now, 28.6),
        vehicle("c", "534", "", now, 28.6));
    assertEquals(2, service.buses("534").buses().size());
    assertEquals("c", service.buses("221").buses().get(0).vehicleId());
  }

  @Test
  void pollingReplacesSnapshotAndDoesNotDuplicate() {
    feed(vehicle("a", "1824", "t", now, 28.6), vehicle("a", "1824", "t", now, 28.6));
    assertEquals(1, service.buses("534").buses().size());
    feed(vehicle("b", "534", "", now, 28.6));
    assertTrue(service.buses("534").buses().isEmpty());
  }

  @Test
  void staleVehicleExcluded() {
    feed(vehicle("old", "1824", "t", now - 121, 28.6));
    assertTrue(service.buses("534").buses().isEmpty());
  }

  @Test
  void incompleteDescriptorResolvesViaTrip() {
    feed(vehicle("a", "", "t", now, 28.6));
    assertEquals(1, service.buses("534").buses().size());
  }

  @Test
  void conflictingDescriptorFailsClosed() {
    feed(vehicle("a", "534", "t", now, 28.6));
    assertTrue(service.buses("534").buses().isEmpty());
    assertFalse(service.buses("534").warnings().isEmpty());
  }

  @Test
  void missingGeometryDoesNotInventEta() {
    feed(vehicle("a", "1824", "t", now, 28.6));
    assertTrue(service.arrivals("s").arrivals().isEmpty());
  }

  @Test
  void tripUpdatesPreferredAndPastStopsExcluded() {
    feed(vehicle("a", "1824", "t", now, 28.6), update("a", 5, 15));
    assertEquals("TRIP_UPDATE", service.arrivals("s").arrivals().get(0).predictionAvailability());
    feed(vehicle("a", "1824", "t", now, 28.6), update("a", -1, 10));
    assertTrue(service.arrivals("s").arrivals().isEmpty());
  }

  @Test
  void geometryEtaRespondsToMovementAndExcludesPassedStop() {
    index.shapes.put(
        "shape",
        List.of(new TransitIndex.Point(28.6, 77.2, 0), new TransitIndex.Point(28.603, 77.2, 1)));
    feed(vehicle("a", "1824", "t", now, 28.6));
    double first = service.arrivals("s").arrivals().get(0).etaMinutes();
    feed(vehicle("a", "1824", "t", now, 28.6005));
    assertTrue(service.arrivals("s").arrivals().get(0).etaMinutes() < first);
    feed(vehicle("a", "1824", "t", now, 28.602));
    assertTrue(service.arrivals("s").arrivals().isEmpty());
  }

  @Test
  void alternativesRequestDestinationAndRespectThreshold() {
    var alternatives = new AlternativeService(index, service, new WalkingService(index));
    assertEquals("DESTINATION_REQUIRED", alternatives.alternatives("s", null, "534").status());
    feed(vehicle("a", "1824", "t", now, 28.6), update("a", 5, 15));
    assertEquals("WAIT_WITHIN_THRESHOLD", alternatives.alternatives("s", "d", "534").status());
    feed(vehicle("a", "1824", "t", now, 28.6), update("a", 20, 30));
    assertEquals("NO_BETTER_OPTION", alternatives.alternatives("s", "d", "534").status());
  }

  @Test
  void routingWithoutCredentialsIsExplicitFallback() {
    var walk = new WalkingService(index).route(28.6, 77.2, index.stops.get("s"));
    assertFalse(walk.available());
    assertNull(walk.durationSeconds());
    assertTrue(walk.maneuvers().isEmpty());
    assertTrue(walk.message().contains("straight-line"));
  }

  @Test
  void gtfsTimesPreserveNextServiceDay() {
    assertEquals(25 * 3600 + 30 * 60, TransitIndex.seconds("25:30:00"));
  }

  @Test
  void recommendsOnlyFasterDestinationServingAlternative() {
    index.routes.put("alt", new TransitIndex.Route("alt", "600", "600UP", "UP", false));
    index.trips.put(
        "t2", new TransitIndex.Trip("t2", "alt", "0", "", index.trips.get("t").visits()));
    index.routesAtStop.put("s", Set.of("1824", "alt"));
    index.routesAtStop.put("d", Set.of("1824", "alt"));
    var second = update("b", 3, 12).toBuilder();
    second.getTripUpdateBuilder().getTripBuilder().setTripId("t2");
    feed(
        vehicle("a", "1824", "t", now, 28.6),
        update("a", 20, 40),
        vehicle("b", "alt", "t2", now, 28.6),
        second.build());
    // Seed spatial lookup through a spy is unnecessary: same-stop alternatives must always be
    // evaluated.
    var result =
        new AlternativeService(index, service, new WalkingService(index))
            .alternatives("s", "d", "534");
    assertEquals("ALTERNATIVES", result.status());
    assertEquals("600", result.options().get(0).legs().get(0).routeNumber());
  }
}
