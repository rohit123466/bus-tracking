package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/** Route-based region filtering: eligibility comes from a route's stops, never a bus's position. */
class RegionServiceTest {
  // Stops: NW (Rohini), N (Civil Lines), S and S2 (south Delhi, outside both regions).
  static final double[] NW = {28.72, 77.05}, N = {28.70, 77.22}, S = {28.55, 77.20}, S2 = {28.50, 77.25};

  TransitIndex index;
  RealtimeSnapshot snapshot;
  LiveTransitService transit;
  RegionService regions;
  long now;

  void route(String routeId, String number, String tripId, String... stopIds) {
    index.routes.put(routeId, new TransitIndex.Route(routeId, number, number + "UP", "UP", true));
    List<TransitIndex.Visit> visits = new ArrayList<>();
    for (int i = 0; i < stopIds.length; i++) {
      visits.add(new TransitIndex.Visit(stopIds[i], i + 1, 36000 + i * 600, 36000 + i * 600));
      index.routesAtStop.computeIfAbsent(stopIds[i], k -> new HashSet<>()).add(routeId);
    }
    index.trips.put(tripId, new TransitIndex.Trip(tripId, routeId, "0", "", visits));
  }

  @BeforeEach
  void setup() {
    now = Instant.now().getEpochSecond();
    index = new TransitIndex();
    snapshot = new RealtimeSnapshot();
    transit = new LiveTransitService(index, snapshot);
    regions = new RegionService(index, transit, snapshot);
    index.addStop(new TransitIndex.Stop("nw", "Rohini", NW[0], NW[1]));
    index.addStop(new TransitIndex.Stop("n", "Civil Lines", N[0], N[1]));
    index.addStop(new TransitIndex.Stop("s", "Saket", S[0], S[1]));
    index.addStop(new TransitIndex.Stop("s2", "Chhatarpur", S2[0], S2[1]));
    route("rA", "100", "tA", "s", "nw"); // south -> north-west
    route("rB", "104", "tB", "s2", "s", "n"); // south -> north
    route("rC", "200", "tC", "s", "s2"); // never touches either region
    route("rD", "300", "tD", "nw", "n"); // both regions
    index.computeRegions();
  }

  FeedEntity vehicle(String id, String route, String trip, double[] at, Integer seq, float speed) {
    return vehicle(id, route, trip, at, seq, speed, null);
  }

  FeedEntity vehicle(
      String id, String route, String trip, double[] at, Integer seq, float speed, Float bearing) {
    var position =
        Position.newBuilder().setLatitude((float) at[0]).setLongitude((float) at[1]).setSpeed(speed);
    if (bearing != null) position.setBearing(bearing);
    var v =
        VehiclePosition.newBuilder()
            .setVehicle(VehicleDescriptor.newBuilder().setId(id))
            .setTrip(TripDescriptor.newBuilder().setRouteId(route).setTripId(trip).setDirectionId(0))
            .setPosition(position)
            .setTimestamp(now);
    if (seq != null) v.setCurrentStopSequence(seq);
    return FeedEntity.newBuilder().setId(id).setVehicle(v).build();
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
  void routeEligibilityComesFromStopsNotBusPosition() {
    assertEquals(Set.of("100", "104", "300"), regions.eligibleNumbers(RegionService.Filter.ANY));
    assertFalse(regions.eligibleNumbers(RegionService.Filter.ANY).contains("200"));
  }

  @Test
  void northAndNorthWestFiltersAreSeparate() {
    assertEquals(Set.of("104", "300"), regions.eligibleNumbers(RegionService.Filter.NORTH));
    assertEquals(Set.of("100", "300"), regions.eligibleNumbers(RegionService.Filter.NORTH_WEST));
  }

  @Test
  void busOutsideBothRegionsIsKeptWhenItsRouteServesThem() {
    // 100 and 104 buses are currently in south Delhi; 200 never serves either region.
    feed(
        vehicle("bus100", "rA", "tA", S, 1, 5),
        vehicle("bus104", "rB", "tB", S2, 1, 5),
        vehicle("bus200", "rC", "tC", S, 1, 5));
    var result = regions.buses(RegionService.Filter.ANY);
    assertEquals(Set.of("bus100", "bus104"),
        new HashSet<>(result.buses().stream().map(RegionService.RegionalBus::vehicleId).toList()));
    assertEquals(1, result.excludedByRegion());
    // Passenger-facing number, not the internal GTFS route_id.
    assertEquals("100", result.buses().stream().filter(b -> b.vehicleId().equals("bus100")).findFirst().get().routeCode());
  }

  @Test
  void regionFilterExcludesBusesFromTheOtherRegion() {
    feed(vehicle("bus100", "rA", "tA", S, 1, 5), vehicle("bus104", "rB", "tB", S, 2, 5));
    var north = regions.buses(RegionService.Filter.NORTH).buses();
    assertEquals(List.of("bus104"), north.stream().map(RegionService.RegionalBus::vehicleId).toList());
    var northWest = regions.buses(RegionService.Filter.NORTH_WEST).buses();
    assertEquals(List.of("bus100"), northWest.stream().map(RegionService.RegionalBus::vehicleId).toList());
  }

  @Test
  void multipleVehiclesOnOneRouteStaySeparate() {
    feed(vehicle("a", "rA", "tA", S, 1, 5), vehicle("b", "rA", "tA", NW, 2, 5));
    var buses = regions.buses(RegionService.Filter.ANY).buses();
    assertEquals(2, buses.size());
    assertTrue(buses.stream().allMatch(b -> b.routeCode().equals("100")));
  }

  // Midpoint of the Saket -> Rohini stop path of route 100 (trip tA).
  static final double[] MID_A = {(S[0] + NW[0]) / 2, (S[1] + NW[1]) / 2};

  Map<String, RegionService.RegionalBus> busesById() {
    var byId = new HashMap<String, RegionService.RegionalBus>();
    regions.buses(RegionService.Filter.ANY).buses().forEach(b -> byId.put(b.vehicleId(), b));
    return byId;
  }

  @Test
  void nextStopPrefersFeedSequenceAndOtherwiseInfersFromGps() {
    feed(vehicle("withSeq", "rA", "tA", S, 1, 10), vehicle("noSeq", "rA", "tA", MID_A, null, 10));
    var byId = busesById();
    assertEquals("Saket", byId.get("withSeq").nextStopName());
    assertEquals(RegionService.SOURCE_FEED, byId.get("withSeq").nextStopSource());
    assertEquals("Rohini", byId.get("noSeq").nextStopName());
    assertEquals(RegionService.SOURCE_INFERRED, byId.get("noSeq").nextStopSource());
    // The fixture's stops are ~24 km apart; a "next stop" beyond 5 km gets no ETA by design.
    assertTrue(byId.get("noSeq").nextStopDistanceMeters() > RegionService.MAX_NEXT_STOP_METERS);
    assertNull(byId.get("noSeq").nextStopEtaMinutes());
  }

  @Test
  void busFarFromItsRoutePathGetsNoInferredStop() {
    // Same latitude as the midpoint but ~7 km east of the Saket -> Rohini line (e.g. at a depot).
    feed(vehicle("depot", "rA", "tA", new double[] {MID_A[0], 77.20}, null, 0));
    var bus = busesById().get("depot");
    assertNotNull(bus, "still shown: the route is eligible and the GPS position is real");
    assertNull(bus.nextStopName());
    assertNull(bus.nextStopEtaMinutes());
  }

  @Test
  void busMovingAgainstTripDirectionGetsNoInferredStop() {
    // Saket -> Rohini heads north-west (~320 deg); a bearing of 140 deg contradicts the trip.
    feed(vehicle("wrongWay", "rA", "tA", MID_A, null, 10, 140f));
    assertNull(busesById().get("wrongWay").nextStopName());
    feed(vehicle("rightWay", "rA", "tA", MID_A, null, 10, 320f));
    assertEquals("Rohini", busesById().get("rightWay").nextStopName());
  }

  @Test
  void inferredUpcomingOnlyForStopsAheadOfTheBus() {
    feed(vehicle("mid", "rA", "tA", MID_A, null, 10));
    var ahead = regions.upcoming("nw", Set.of("100"), new ArrayList<>());
    assertEquals(1, ahead.size());
    assertEquals("STOP_CHAIN_ESTIMATE", ahead.get(0).availability());
    // ~12 km of the ~24 km path remains at 36 km/h -> roughly 20 minutes.
    assertEquals(20, ahead.get(0).etaMinutes(), 3);
    assertEquals(0, ahead.get(0).remainingStops());
    // Saket is behind the bus, so it is not an upcoming arrival there.
    assertTrue(regions.upcoming("s", Set.of("100"), new ArrayList<>()).isEmpty());
  }

  @Test
  void stationaryInferredBusIsListedWithoutAnEta() {
    feed(vehicle("parked", "rA", "tA", MID_A, null, 0));
    var upcoming = regions.upcoming("nw", Set.of("100"), new ArrayList<>());
    assertEquals(1, upcoming.size());
    assertNull(upcoming.get(0).etaMinutes());
    assertEquals("ETA unavailable", upcoming.get(0).label());
  }

  @Test
  void unknownFeedTripFallsBackToTheRoutesSingleStopPattern() {
    // The live feed's trip_id is not in the static GTFS (real-world: static feed is outdated).
    feed(vehicle("newTrip", "rA", "rA_23_99", MID_A, null, 10));
    var bus = busesById().get("newTrip");
    assertEquals("100", bus.routeCode());
    assertEquals("Rohini", bus.nextStopName());
    assertEquals(RegionService.SOURCE_INFERRED, bus.nextStopSource());
    var upcoming = regions.upcoming("nw", Set.of("100"), new ArrayList<>());
    assertEquals(List.of("newTrip"), upcoming.stream().map(RegionService.Upcoming::vehicleId).toList());
  }

  @Test
  void unknownTripWithAmbiguousStopPatternsStaysUnresolved() {
    // Route rA also runs the reverse pattern; without a bearing both fit the GPS position.
    index.trips.put("tA_rev", new TransitIndex.Trip("tA_rev", "rA", "0", "", List.of(
        new TransitIndex.Visit("nw", 1, 36000, 36000), new TransitIndex.Visit("s", 2, 36600, 36600))));
    feed(vehicle("ambiguous", "rA", "rA_23_99", MID_A, null, 10));
    assertNull(busesById().get("ambiguous").nextStopName());
    assertTrue(regions.upcoming("nw", Set.of("100"), new ArrayList<>()).isEmpty());
    // A bearing disambiguates: heading north-west only fits the Saket -> Rohini pattern.
    feed(vehicle("heading", "rA", "rA_23_99", MID_A, null, 10, 320f));
    assertEquals("Rohini", busesById().get("heading").nextStopName());
  }

  @Test
  void ineligibleRouteIsNeverAnUpcomingArrival() {
    feed(vehicle("mid", "rA", "tA", MID_A, null, 10));
    assertTrue(regions.upcoming("nw", Set.of("300"), new ArrayList<>()).isEmpty());
  }

  @Test
  void nextStopEtaNeedsUsableSpeed() {
    // 28.5501N is ~11 m from the stop; speed 0 must not yield an invented ETA.
    feed(vehicle("stopped", "rA", "tA", new double[] {28.5501, 77.20}, 1, 0));
    var bus = regions.buses(RegionService.Filter.ANY).buses().get(0);
    assertEquals("Saket", bus.nextStopName());
    assertNull(bus.nextStopEtaMinutes());
    assertNull(bus.nextStopEtaBasis());
  }

  @Test
  void upcomingEstimateIsLabelledAndAbsentWithoutSpeed() {
    // No TripUpdates and no shapes: the only possible estimate is the labelled stop-chain one.
    feed(vehicle("moving", "rA", "tA", S, 1, 10), vehicle("still", "rA", "tA", S, 1, 0));
    var warnings = new ArrayList<String>();
    var upcoming = regions.upcoming("nw", Set.of("100"), warnings);
    var byVehicle = new HashMap<String, RegionService.Upcoming>();
    upcoming.forEach(u -> byVehicle.put(u.vehicleId(), u));
    assertEquals("STOP_CHAIN_ESTIMATE", byVehicle.get("moving").availability());
    assertNotNull(byVehicle.get("moving").etaMinutes());
    assertTrue(byVehicle.get("moving").label().startsWith("Rough estimate"));
    assertEquals("UNAVAILABLE", byVehicle.get("still").availability());
    assertNull(byVehicle.get("still").etaMinutes());
    assertEquals("ETA unavailable", byVehicle.get("still").label());
  }

  @Test
  void nearbyStopsOnlyIncludeEligibleRoutes() {
    // Standing at Saket: stop "s" is served by 100, 104 (eligible) and 200 (not).
    var result = regions.nearbyStops(S[0], S[1], 500, 5, RegionService.Filter.ANY);
    var stop = result.stops().stream().filter(x -> x.id().equals("s")).findFirst().orElseThrow();
    assertEquals(List.of("100", "104"), stop.routeNumbers());
    assertEquals("STRAIGHT_LINE_ESTIMATE", stop.walkBasis());
    // Chhatarpur is only served by 104 and 200 -> still listed because 104 is eligible.
    var south2 = regions.nearbyStops(S2[0], S2[1], 300, 5, RegionService.Filter.ANY);
    assertEquals(List.of("104"), south2.stops().get(0).routeNumbers());
    // NORTH_WEST excludes 104, so the stop with only 104 disappears.
    assertTrue(regions.nearbyStops(S2[0], S2[1], 300, 5, RegionService.Filter.NORTH_WEST).stops().isEmpty());
  }

  @Test
  void invalidRegionIsRejected() {
    assertThrows(ResponseStatusException.class, () -> RegionService.Filter.parse("EAST"));
    assertEquals(RegionService.Filter.ANY, RegionService.Filter.parse(""));
    assertEquals(RegionService.Filter.NORTH_WEST, RegionService.Filter.parse("north-west"));
  }
}
