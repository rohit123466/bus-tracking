package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/** Pick-up -> destination route matching: stop order, direction, dedup, walking, live buses. */
class StandRouteServiceTest {
  TransitIndex index;
  RealtimeSnapshot snapshot;
  LiveTransitService transit;
  RegionService regions;
  StandService stands;
  StandRouteService finder;
  long now;

  // An east-west road: 0.01 degrees of longitude is about 976 m here. Kashmere Gate is east.
  static final double LAT = 28.70;

  void stop(String id, String name, double lng) {
    index.addStop(new TransitIndex.Stop(id, name, LAT, lng));
  }

  /** A GTFS route_id; each call adds one trip, starting at `startSod`, 10 min per stop. */
  void trip(String routeId, String number, String direction, String tripId, int startSod, String... stopIds) {
    index.routes.putIfAbsent(routeId, new TransitIndex.Route(routeId, number, number + direction, direction, false));
    List<TransitIndex.Visit> visits = new ArrayList<>();
    for (int i = 0; i < stopIds.length; i++) {
      int t = startSod + i * 600;
      visits.add(new TransitIndex.Visit(stopIds[i], i, t, t));
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
    regions = new RegionService(index, transit, snapshot, new RouteLearner(index, transit));
    stands = new StandService(index, transit, snapshot, regions);
    finder = new StandRouteService(index, stands, regions, transit, snapshot);

    // Kashmere Gate: one stop on each side of the road (one stand), plus a differently named
    // terminal stop 150 m away.
    stop("kg1", "ISBT Kashmere Gate", 77.200);
    stop("kg2", "ISBT Kashmiri Gate", 77.2002);
    stop("kgx", "Kashmere Gate City Bus Terminal", 77.2015);
    stop("mt", "Model Town", 77.190);
    stop("az", "Azadpur", 77.180);
    stop("jp", "Jahangirpuri", 77.170);
    stop("r18", "Rohini Sec 18", 77.160);
    stop("nar", "Narela", 77.140);
    index.addStop(new TransitIndex.Stop("cl", "Civil Lines", LAT + 0.02, 77.21));

    // The task's example: 100 and 102 run Kashmere Gate -> Rohini Sec 18; 106 the other way;
    // 120 never reaches Rohini Sec 18.
    trip("r100up", "100", "UP", "t100a", 36000, "kg1", "az", "jp", "r18", "nar");
    trip("r100up", "100", "UP", "t100b", 39600, "kg1", "az", "jp", "r18", "nar");
    trip("r100up", "100", "UP", "t100short", 43200, "kg1", "az", "jp", "r18"); // short working
    trip("r102", "102", "UP", "t102", 36000, "kg1", "mt", "az", "r18");
    trip("r106", "106", "DOWN", "t106", 36000, "r18", "az", "kg2");
    trip("r120", "120", "UP", "t120", 36000, "kg1", "az", "cl");
    // The same number back the other way, as its own route_id.
    trip("r100down", "100", "DOWN", "t100d", 36000, "nar", "r18", "jp", "az", "kg2");
    // Two route_ids of one number running the same stops: one choice for the rider.
    trip("r116a", "116", "UP", "t116a", 36000, "kg2", "az", "r18");
    trip("r116b", "116", "UP2", "t116b", 37800, "kg2", "az", "r18");
    // Only from the terminal stop next door.
    trip("r999", "999", "UP", "t999", 36000, "kgx", "jp", "r18");
  }

  FeedEntity bus(String id, String route, double lng, double speedKmh, float bearing) {
    var position = Position.newBuilder()
        .setLatitude((float) LAT).setLongitude((float) lng)
        .setSpeed((float) (speedKmh / 3.6)).setBearing(bearing);
    var v = VehiclePosition.newBuilder()
        .setVehicle(VehicleDescriptor.newBuilder().setId(id))
        .setTrip(TripDescriptor.newBuilder().setRouteId(route).setTripId("live-" + id))
        .setPosition(position)
        .setTimestamp(now);
    return FeedEntity.newBuilder().setId(id).setVehicle(v).build();
  }

  void feed(FeedEntity... entities) {
    snapshot.accept(
        FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now))
            .addAllEntity(List.of(entities))
            .build(),
        true, true);
  }

  static List<String> numbers(StandRouteService.RouteSearch r) {
    return r.routes().stream().map(StandRouteService.RouteMatch::routeNumber).sorted().toList();
  }

  static StandRouteService.RouteMatch route(StandRouteService.RouteSearch r, String number) {
    return r.routes().stream().filter(m -> m.routeNumber().equals(number)).findFirst().orElseThrow();
  }

  static List<String> stopIds(StandRouteService.RouteMatch m) {
    return m.stops().stream().map(StandRouteService.RouteStop::id).toList();
  }

  // ── Test 1 & 2: direction ───────────────────────────────────────────────

  @Test
  void onlyRoutesReachingTheDestinationAfterThePickUpAreListed() {
    var r = finder.search("kg1", "r18", 0);
    assertEquals(StandRouteService.STATUS_OK, r.status());
    // 106 runs the other way and 120 never reaches Rohini Sec 18.
    assertEquals(List.of("100", "102", "116"), numbers(r));

    var r100 = route(r, "100");
    assertEquals(List.of("r100up"), r100.routeIds());
    assertEquals(List.of("kg1", "az", "jp", "r18"), stopIds(r100));
    assertEquals(2, r100.intermediateStopCount());
    assertEquals("ISBT Kashmere Gate", r100.origin());
    assertEquals("Narela", r100.terminus());
    assertEquals(1, r100.pickupStopNumber());
    assertEquals(5, r100.routeStopCount());
    assertEquals(List.of("kg1", "mt", "az", "r18"), stopIds(route(r, "102")));
  }

  @Test
  void theReverseTripListsOnlyRoutesRunningThatWay() {
    var r = finder.search("r18", "kg1", 0);
    assertEquals(List.of("100", "106"), numbers(r));
    var r100 = route(r, "100");
    assertEquals(List.of("r100down"), r100.routeIds()); // not the UP route_id
    assertEquals("DOWN", r100.direction());
    // Arrives on the other side of the road: still the selected stand.
    assertEquals("kg2", r100.destination().id());
    assertTrue(r100.destinationAtSelectedStand());
  }

  // ── Test 3: several routes, no duplicates ───────────────────────────────

  @Test
  void patternsAndRouteIdsWithTheSameStopsAreOneResult() {
    var r = finder.search("kg2", "r18", 0);
    assertEquals(r.routes().size(), new HashSet<>(r.routes().stream().map(StandRouteService.RouteMatch::key).toList()).size());
    var r116 = route(r, "116");
    assertEquals(List.of("r116a", "r116b"), r116.routeIds());
    assertEquals(2, r116.tripsPerDay());
    assertEquals(1, r.routes().stream().filter(m -> m.routeNumber().equals("116")).count());

    // The short working rides the same stops: its trip counts, it is not a second card.
    var r100 = route(r, "100");
    assertEquals(3, r100.tripsPerDay());
    assertEquals(1, r100.variants());
    assertEquals("10:00", r100.firstDeparture());
    assertEquals("12:00", r100.lastDeparture());
    // Three stops of 10 min each in the timetable; no shapes, so straight lines between stops.
    assertEquals(30.0, r100.scheduledMinutes());
    assertEquals(StandRouteService.TIME_SCHEDULED, r100.scheduledBasis());
    assertEquals(StandRouteService.DISTANCE_STOP_TO_STOP, r100.distanceBasis());
    assertEquals(3.9, r100.distanceKm(), 0.1);
  }

  // ── Test 4: no connection ───────────────────────────────────────────────

  @Test
  void noConnectingRouteSaysSoAndMentionsRoutesRunningTheOtherWay() {
    var none = finder.search("nar", "mt", 0);
    assertEquals(StandRouteService.STATUS_NO_ROUTES, none.status());
    assertTrue(none.routes().isEmpty());
    assertFalse(none.message().contains("other way"));

    var reverse = finder.search("cl", "kg1", 0);
    assertEquals(StandRouteService.STATUS_NO_ROUTES, reverse.status());
    assertTrue(reverse.message().contains("other way"), reverse.message());
    assertEquals(1, reverse.reverseRouteCount());
    assertEquals(0, none.reverseRouteCount());
  }

  @Test
  void aStandNoTimetabledRouteCallsAtIsReported() {
    stop("lonely", "Lonely Stop", 77.30);
    assertEquals(StandRouteService.STATUS_NO_ROUTES_AT_PICKUP, finder.search("lonely", "r18", 0).status());
    assertEquals(StandRouteService.STATUS_NO_ROUTES_AT_DESTINATION, finder.search("kg1", "lonely", 0).status());
  }

  @Test
  void theSameStandTwiceIsRejected() {
    // kg1 and kg2 are the two sides of one stand.
    var r = finder.search("kg1", "kg2", 0);
    assertEquals(StandRouteService.STATUS_SAME_STAND, r.status());
    assertTrue(r.routes().isEmpty());
  }

  @Test
  void unknownStopsAre404() {
    assertThrows(ResponseStatusException.class, () -> finder.search("nope", "r18", 0));
    assertThrows(ResponseStatusException.class, () -> finder.live("nope", List.of("r100up")));
    assertThrows(ResponseStatusException.class, () -> finder.live("kg1", List.of("nope")));
  }

  // ── Nearby stops ────────────────────────────────────────────────────────

  @Test
  void stopsWithinTheWalkingDistanceAreUsedAndLabelled() {
    assertFalse(numbers(finder.search("kg1", "r18", 0)).contains("999"));
    var r = finder.search("kg1", "r18", 300);
    var r999 = route(r, "999");
    assertEquals("kgx", r999.pickup().id());
    assertFalse(r999.pickupAtSelectedStand());
    assertEquals(127, r999.pickupWalkMeters(), 5); // from the nearer side of the stand
    // A route calling at the stand itself still boards there, not at the stop next door.
    assertTrue(route(r, "100").pickupAtSelectedStand());
    assertEquals(0, route(r, "100").pickupWalkMeters());
  }

  @Test
  void aLoopBoardsAtTheVisitClosestToTheDestination() {
    // Calls at Kashmere Gate, goes round, and calls again just before Rohini Sec 18.
    trip("r500", "500", "", "t500", 36000, "kg1", "mt", "az", "kg1", "r18");
    var r500 = route(finder.search("kg1", "r18", 0), "500");
    assertEquals(List.of("kg1", "r18"), stopIds(r500));
    assertEquals(4, r500.pickupStopNumber());
  }

  @Test
  void bestRidePrefersStandStopsAndShortRides() {
    var p = new StandRouteService.Pattern(0, "r", new String[] {"a", "b", "c", "d"}, List.of());
    // Board at "a" (stand) or "b" (nearby, 200 m): the stand stop wins, one stop is cheaper than 200 m.
    var ride = StandRouteService.bestRide(p, Map.of("a", 0.0, "b", 200.0), Map.of("d", 0.0));
    assertEquals(0, ride.from());
    // Equal walks: the later boarding rides fewer stops.
    ride = StandRouteService.bestRide(p, Map.of("a", 0.0, "b", 0.0), Map.of("d", 0.0));
    assertEquals(1, ride.from());
    // Destination before every pick-up stop: no ride.
    assertNull(StandRouteService.bestRide(p, Map.of("c", 0.0), Map.of("a", 0.0)));
  }

  @Test
  void nextDeparturesWrapPastMidnight() {
    var next = StandRouteService.nextDepartures(new int[] {600, 36000, 86000}, 1_000_000, 85000);
    assertEquals(List.of("23:53", "00:10", "10:00"), next.stream().map(StandRouteService.Departure::time).toList());
    assertEquals(1000.0 / 60, next.get(0).minutesUntil(), 0.1);
  }

  // ── Test 5: live buses are separate from routes ─────────────────────────

  @Test
  void aRouteWithNoLiveBusIsStillListed() {
    feed(); // feed up, no buses
    var r = finder.search("kg1", "r18", 0);
    assertEquals(0, route(r, "100").liveBusCount());
    assertEquals(0, r.routesWithLiveBuses());
    var live = finder.live("kg1", List.of("r100up"));
    assertTrue(live.buses().isEmpty());
    assertEquals("No live buses currently available on this route.", live.message());
  }

  @Test
  void liveBusesAreCountedPerRouteIdAndTimedToThePickUp() {
    feed(
        // 102 between Model Town and Azadpur, heading west towards Azadpur.
        bus("b1", "r102", 77.185, 20, 270),
        // 100 going the other way (DOWN): never counted on the UP route.
        bus("b2", "r100down", 77.165, 20, 90));
    var r = finder.search("kg1", "r18", 0);
    assertEquals(1, route(r, "102").liveBusCount());
    assertEquals(0, route(r, "100").liveBusCount());
    assertEquals(1, r.routesWithLiveBuses());

    var toAzadpur = finder.live("az", List.of("r102"));
    assertEquals(1, toAzadpur.buses().size());
    var b = toAzadpur.buses().get(0);
    assertEquals(StandRouteService.PICKUP_AHEAD, b.pickupState());
    assertNotNull(b.etaMinutes());
    assertEquals("102", b.routeNumber());
    assertEquals("az", b.nextStopId());
    assertEquals(1, toAzadpur.headingToPickup());
    assertNull(toAzadpur.message());

    // Model Town is behind it.
    var b2 = finder.live("mt", List.of("r102")).buses().get(0);
    assertEquals(StandRouteService.PICKUP_PASSED, b2.pickupState());
    assertNull(b2.etaMinutes());
  }
  // ── One change ──────────────────────────────────────────────────────────

  @Test
  void withNoDirectBusJourneysWithOneChangeAreListed() {
    // Narela -> Civil Lines: 100 (DOWN) to Azadpur, then 120 from Azadpur.
    var r = finder.search("nar", "cl", 0);
    assertEquals(StandRouteService.STATUS_NO_ROUTES, r.status());
    assertEquals(1, r.transfers().size());
    var t = r.transfers().get(0);
    assertEquals("100", t.first().routeNumber());
    assertEquals(List.of("r100down"), t.first().routeIds());
    assertEquals("nar", t.first().pickup().id());
    // Changes at Azadpur, the quickest place to meet 120 (not Kashmere Gate, further along).
    assertEquals("az", t.first().destination().id());
    assertEquals("120", t.second().routeNumber());
    assertEquals("az", t.second().pickup().id());
    assertEquals("cl", t.second().destination().id());
    assertEquals(0, t.transferWalkMeters());
    // 3 stops x 10 min, then 1 stop x 10 min, in the timetable; waiting is not included.
    assertEquals(40.0, t.totalMinutes());
    assertEquals(4, t.totalStops());
  }

  @Test
  void aChangeCanBeAShortWalkToAnotherStop() {
    // Rohini Sec 18 -> Civil Lines only via the terminal stop next door to Kashmere Gate.
    trip("r700", "700", "", "t700", 36000, "r18", "jp", "kgx");
    trip("r800", "800", "", "t800", 36000, "kg1", "cl");
    var r = finder.search("r18", "cl", 0);
    var viaWalk = r.transfers().stream().filter(t -> t.first().routeNumber().equals("700")).findFirst().orElseThrow();
    assertEquals("kgx", viaWalk.first().destination().id());
    assertEquals("800", viaWalk.second().routeNumber());
    assertTrue(viaWalk.transferWalkMeters() > 100 && viaWalk.transferWalkMeters() <= 200, "walk " + viaWalk.transferWalkMeters());
    // Every option changes between two different route numbers, each leg in its own direction.
    for (var t : r.transfers()) {
      assertNotEquals(t.first().routeNumber(), t.second().routeNumber());
      assertEquals(t.first().stops().get(0).id(), t.first().pickup().id());
    }
  }

  @Test
  void enoughDirectRoutesMeanNoChanges() {
    var r = finder.search("kg1", "r18", 0);
    assertTrue(r.routes().size() >= StandRouteService.TRANSFERS_WHEN_DIRECT_BELOW);
    assertTrue(r.transfers().isEmpty());
  }
}
