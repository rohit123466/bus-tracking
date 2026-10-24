package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/** Region filtering: route eligibility comes from its stops; the Live Map also requires the bus inside the area. */
class RegionServiceTest {
  // Stops: NW (Rohini), N (Civil Lines), S and S2 (south Delhi, outside both regions).
  static final double[] NW = {28.72, 77.05}, N = {28.70, 77.22}, S = {28.55, 77.20}, S2 = {28.50, 77.25};

  TransitIndex index;
  RealtimeSnapshot snapshot;
  LiveTransitService transit;
  RegionService regions;
  RouteLearner learner;
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
    learner = new RouteLearner(index, transit);
    regions = new RegionService(index, transit, snapshot, learner);
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
  void withAreaFilterOffBusOutsideBothRegionsIsKeptWhenItsRouteServesThem() {
    // 100 and 104 buses are currently in south Delhi; 200 never serves either region.
    feed(
        vehicle("bus100", "rA", "tA", S, 1, 5),
        vehicle("bus104", "rB", "tB", S2, 1, 5),
        vehicle("bus200", "rC", "tC", S, 1, 5));
    var result = regions.buses(RegionService.Filter.ANY, "", false);
    assertEquals(Set.of("bus100", "bus104"),
        new HashSet<>(result.buses().stream().map(RegionService.RegionalBus::vehicleId).toList()));
    assertEquals(1, result.excludedByRegion());
    // Passenger-facing number, not the internal GTFS route_id.
    assertEquals("100", result.buses().stream().filter(b -> b.vehicleId().equals("bus100")).findFirst().get().routeCode());
  }

  @Test
  void regionFilterExcludesBusesFromTheOtherRegion() {
    feed(vehicle("bus100", "rA", "tA", S, 1, 5), vehicle("bus104", "rB", "tB", S, 2, 5));
    var north = regions.buses(RegionService.Filter.NORTH, "", false).buses();
    assertEquals(List.of("bus104"), north.stream().map(RegionService.RegionalBus::vehicleId).toList());
    var northWest = regions.buses(RegionService.Filter.NORTH_WEST, "", false).buses();
    assertEquals(List.of("bus100"), northWest.stream().map(RegionService.RegionalBus::vehicleId).toList());
  }

  @Test
  void busesOutsideTheAreaAreHiddenByDefault() {
    feed(
        vehicle("100south", "rA", "tA", S, 1, 5), // eligible route, but in south Delhi
        vehicle("100rohini", "rA", "tA", NW, 2, 5),
        vehicle("104civil", "rB", "tB", N, 3, 5),
        vehicle("200rohini", "rC", "tC", NW, 1, 5)); // inside, but route never serves the area
    var any = regions.buses(RegionService.Filter.ANY);
    assertEquals(Set.of("100rohini", "104civil"),
        new HashSet<>(any.buses().stream().map(RegionService.RegionalBus::vehicleId).toList()));
    assertEquals(1, any.excludedOutsideArea());
    assertEquals(1, any.excludedByRegion());
    // Each filter only keeps buses inside its own area.
    assertEquals(List.of("104civil"), regions.buses(RegionService.Filter.NORTH).buses().stream()
        .map(RegionService.RegionalBus::vehicleId).toList());
    assertEquals(List.of("100rohini"), regions.buses(RegionService.Filter.NORTH_WEST).buses().stream()
        .map(RegionService.RegionalBus::vehicleId).toList());
  }

  @Test
  void searchedRouteIsAlsoLimitedToTheArea() {
    feed(vehicle("100south", "rA", "tA", S, 1, 5), vehicle("100rohini", "rA", "tA", NW, 2, 5),
        vehicle("104civil", "rB", "tB", N, 3, 5));
    var result = regions.buses(RegionService.Filter.ANY, "100", true);
    assertEquals(List.of("100rohini"),
        result.buses().stream().map(RegionService.RegionalBus::vehicleId).toList());
    assertEquals(1, result.excludedOutsideArea());
  }

  @Test
  void multipleVehiclesOnOneRouteStaySeparate() {
    feed(vehicle("a", "rA", "tA", S, 1, 5), vehicle("b", "rA", "tA", NW, 2, 5));
    var buses = regions.buses(RegionService.Filter.ANY, "", false).buses();
    assertEquals(2, buses.size());
    assertTrue(buses.stream().allMatch(b -> b.routeCode().equals("100")));
  }

  // Midpoint of the Saket -> Rohini stop path of route 100 (trip tA).
  static final double[] MID_A = {(S[0] + NW[0]) / 2, (S[1] + NW[1]) / 2};

  Map<String, RegionService.RegionalBus> busesById() {
    var byId = new HashMap<String, RegionService.RegionalBus>();
    regions.buses(RegionService.Filter.ANY, "", false).buses().forEach(b -> byId.put(b.vehicleId(), b));
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
  void stoppedInferredBusGetsALabelledTypicalSpeedEstimate() {
    feed(vehicle("parked", "rA", "tA", MID_A, null, 0));
    var upcoming = regions.upcoming("nw", Set.of("100"), new ArrayList<>());
    assertEquals(1, upcoming.size());
    assertEquals("TYPICAL_SPEED_ESTIMATE", upcoming.get(0).availability());
    // ~12 km left at the typical 17 km/h -> roughly 42 minutes.
    assertEquals(42, upcoming.get(0).etaMinutes(), 4);
    assertTrue(upcoming.get(0).label().contains("typical city-bus speed"));
  }

  @Test
  void upcomingBusCarriesItsLivePositionAnywhereInDelhi() {
    // MID_A is outside both areas, but the bus is heading to Rohini (inside North-West).
    feed(vehicle("mid", "rA", "tA", MID_A, null, 10));
    var u = regions.upcoming("nw", Set.of("100"), new ArrayList<>()).get(0);
    assertEquals(MID_A[0], u.busLatitude(), 1e-4);
    assertEquals(MID_A[1], u.busLongitude(), 1e-4);
    assertEquals(36, u.speedKmh(), 0.5); // feed speed is m/s: 10 m/s = 36 km/h
    assertEquals(12000, u.distanceMeters(), 1500);
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
  void routeCheckSaysWhetherTheGpsFixFitsTheRouteNumberShown() {
    feed(
        vehicle("known", "rA", "tA", new double[] {MID_A[0], 77.20}, null, 0), // timetable trip
        vehicle("onPath", "rA", "rA_23_99", MID_A, null, 10), // unknown trip, on 100's path
        vehicle("farAway", "rA", "rA_23_98", new double[] {MID_A[0], 77.20}, null, 0)); // ~7 km off
    var byId = busesById();
    assertEquals(RegionService.CHECK_TRIP, byId.get("known").routeCheck());
    assertEquals(RegionService.CHECK_ON_ROUTE, byId.get("onPath").routeCheck());
    var far = byId.get("farAway");
    assertEquals(RegionService.CHECK_OFF_ROUTE, far.routeCheck());
    // Still shown under the number the feed gives it, never relabelled to a nearer route.
    assertEquals("100", far.routeCode());
    assertTrue(regions.buses(RegionService.Filter.ANY, "", false).warnings().stream()
        .anyMatch(w -> w.contains("1 shown buses") && w.contains("unconfirmed")));
  }

  @Test
  void routeWithoutTimetableStopsCannotBeChecked() {
    index.routes.put("rE", new TransitIndex.Route("rE", "100", "100UP", "UP", true));
    feed(vehicle("bare", "rE", "rE_1", MID_A, null, 10));
    assertEquals(RegionService.CHECK_NO_STOPS, busesById().get("bare").routeCheck());
  }

  @Test
  void upcomingArrivalCarriesTheRouteCheck() {
    feed(vehicle("onPath", "rA", "rA_23_99", MID_A, null, 10));
    var u = regions.upcoming("nw", Set.of("100"), new ArrayList<>()).get(0);
    assertEquals(RegionService.CHECK_ON_ROUTE, u.routeCheck());
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
    var bus = regions.buses(RegionService.Filter.ANY, "", false).buses().get(0);
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
  void nearbyStopsAreInsideTheAreaAndOnlyListEligibleRoutes() {
    // Rohini is served by 100 and 300, both eligible.
    var result = regions.nearbyStops(NW[0], NW[1], 500, 5, RegionService.Filter.ANY);
    var stop = result.stops().stream().filter(x -> x.id().equals("nw")).findFirst().orElseThrow();
    assertEquals(List.of("100", "300"), stop.routeNumbers());
    assertEquals("STRAIGHT_LINE_ESTIMATE", stop.walkBasis());
    // Saket is served by eligible routes 100 and 104 but lies outside both areas.
    assertTrue(regions.nearbyStops(S[0], S[1], 500, 5, RegionService.Filter.ANY).stops().isEmpty());
    // Rohini is in North-West, not North, so the NORTH filter drops it.
    assertTrue(regions.nearbyStops(NW[0], NW[1], 500, 5, RegionService.Filter.NORTH).stops().isEmpty());
    // Civil Lines is in North; 104 and 300 are North-eligible.
    var north = regions.nearbyStops(N[0], N[1], 500, 5, RegionService.Filter.NORTH);
    assertEquals(List.of("104", "300"), north.stops().get(0).routeNumbers());
  }

  /**
   * Route 500's static GTFS lists only Saket, but its buses are seen driving due north along
   * 77.10 through Rohini (one poll per 10 s, ~111 m per poll), passing L1 then L2.
   */
  void learnRoute500() {
    route("rL", "500", "tL", "s");
    index.addStop(new TransitIndex.Stop("L1", "Rohini Sec 3", 28.705, 77.1002));
    index.addStop(new TransitIndex.Stop("L2", "Rohini Sec 7", 28.735, 77.0999));
    index.computeRegions();
    assertFalse(regions.eligibleNumbers(RegionService.Filter.ANY).contains("500"));
    long t = now - 5000;
    for (int i = 0; i <= 40; i++, t += 10)
      learner.observe(List.of(new RealtimeSnapshot.Vehicle(
          "teacher", "rL", "tL", "0", null, 28.70 + i * 0.001, 77.10, 0.0, null, t)), t);
  }

  @Test
  void learnedPathGivesEtaToAStopTheStaticGtfsDoesNotList() {
    learnRoute500();
    feed(vehicle("live500", "rL", "tL", new double[] {28.715, 77.10}, null, 10, 0f)); // northbound
    var up = regions.upcoming("L2", Set.of("500"), new ArrayList<>());
    assertEquals(List.of("live500"), up.stream().map(RegionService.Upcoming::vehicleId).toList());
    assertEquals("LEARNED_ROUTE_ESTIMATE", up.get(0).availability());
    // The recorded bus took 200 s from 28.715 to L2 at 28.735.
    assertEquals(200 / 60.0, up.get(0).etaMinutes(), 0.2);
    assertEquals(2220, up.get(0).distanceMeters(), 120);
    assertEquals(0, up.get(0).remainingStops());
    // L1 is behind the bus.
    assertTrue(regions.upcoming("L1", Set.of("500"), new ArrayList<>()).isEmpty());
    // The map popup's next stop comes from the learned path too.
    var bus = busesById().get("live500");
    assertEquals("Rohini Sec 7", bus.nextStopName());
    assertEquals(RegionService.SOURCE_LEARNED, bus.nextStopSource());
    assertEquals("LEARNED_ROUTE_ESTIMATE", bus.nextStopEtaBasis());
  }

  @Test
  void learnedPathArrivalFarFromTheTimetableRouteIsMarkedUnconfirmed() {
    learnRoute500();
    // A trip the timetable lacks: the number 500 comes only from the feed's route_id, and the
    // learned path was built from that same route_id, so it cannot confirm the number.
    feed(vehicle("live500", "rL", "rL_23_7", new double[] {28.715, 77.10}, null, 10, 0f));
    var up = regions.upcoming("L2", Set.of("500"), new ArrayList<>());
    assertEquals("LEARNED_ROUTE_ESTIMATE", up.get(0).availability());
    assertEquals(RegionService.CHECK_OFF_ROUTE, up.get(0).routeCheck());
  }

  @Test
  void busDrivingAgainstTheLearnedPathIsNotComing() {
    learnRoute500();
    feed(vehicle("south", "rL", "tL", new double[] {28.715, 77.10}, null, 10, 180f));
    assertTrue(regions.upcoming("L2", Set.of("500"), new ArrayList<>()).isEmpty());
  }

  @Test
  void busOffTheLearnedPathIsNotComing() {
    learnRoute500();
    feed(vehicle("elsewhere", "rL", "tL", new double[] {28.715, 77.13}, null, 10, 0f)); // ~3 km east
    assertTrue(regions.upcoming("L2", Set.of("500"), new ArrayList<>()).isEmpty());
  }

  @Test
  void learnedStopsMakeTheRouteEligibleAndListedAtTheStop() {
    learnRoute500();
    assertTrue(regions.eligibleNumbers(RegionService.Filter.NORTH_WEST).contains("500"));
    var stops = regions.nearbyStops(28.735, 77.0999, 200, 5, RegionService.Filter.ANY);
    assertEquals("L2", stops.stops().get(0).id());
    assertEquals(List.of("500"), stops.stops().get(0).routeNumbers());
  }

  @Test
  void invalidRegionIsRejected() {
    assertThrows(ResponseStatusException.class, () -> RegionService.Filter.parse("EAST"));
    assertEquals(RegionService.Filter.ANY, RegionService.Filter.parse(""));
    assertEquals(RegionService.Filter.NORTH_WEST, RegionService.Filter.parse("north-west"));
  }
}
