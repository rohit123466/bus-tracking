package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.google.transit.realtime.GtfsRealtime.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

/** Bus-stand search (grouping, partial names) and live/scheduled arrivals at a stand. */
class StandServiceTest {
  TransitIndex index;
  RealtimeSnapshot snapshot;
  LiveTransitService transit;
  RegionService regions;
  StandService stands;
  long now;

  // An east-west road in North-West Delhi: 0.01° of longitude ≈ 976 m here.
  static final double LAT = 28.70;

  void stop(String id, String name, double lat, double lng) {
    index.addStop(new TransitIndex.Stop(id, name, lat, lng));
  }

  /** A GTFS route with one trip calling at the stops, `startSod` + 10 min per stop. */
  void route(String routeId, String number, String tripId, int startSod, String... stopIds) {
    index.routes.put(routeId, new TransitIndex.Route(routeId, number, number + "UP", "UP", false));
    List<TransitIndex.Visit> visits = new ArrayList<>();
    for (int i = 0; i < stopIds.length; i++) {
      int t = startSod + i * 600;
      visits.add(new TransitIndex.Visit(stopIds[i], i + 1, t, t));
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

    stop("a", "Rohini Sec 18 B7 Road", LAT, 77.100);
    // One stand: two sides of the road, spelled differently.
    stop("b1", "Azadpur Terminal", LAT, 77.110);
    stop("b2", "AZADPUR  TERMINAL", LAT + 0.0005, 77.1101);
    stop("c", "ISBT Kashmiri Gate", LAT, 77.120);
    // Same name, but 5 km away: a different stand.
    stop("far", "Azadpur Terminal", LAT + 0.045, 77.110);
    stop("metro", "Azadpur Metro Station", LAT + 0.001, 77.111);
    stop("x", "Kashmere Gate City Bus Terminal", LAT + 0.002, 77.121);

    // 100 runs west -> east through the stand (stop b1); 200 runs east -> west (stop b2).
    route("r100", "100", "t100", 36000, "a", "b1", "c");
    route("r200", "200", "t200", 36000, "c", "b2", "a");
    route("r300", "300", "t300", 36000, "far", "metro");
    index.routeDestinations.put("r100", "Kashmere Gate");
    index.routeDestinations.put("r200", "Rohini Sec 18");
  }

  FeedEntity bus(String id, String route, double lng, double speedKmh, float bearing) {
    return bus(id, route, lng, speedKmh, bearing, now);
  }

  FeedEntity bus(String id, String route, double lng, double speedKmh, float bearing, long at) {
    var position =
        Position.newBuilder()
            .setLatitude((float) LAT)
            .setLongitude((float) lng)
            .setSpeed((float) (speedKmh / 3.6))
            .setBearing(bearing);
    var v =
        VehiclePosition.newBuilder()
            .setVehicle(VehicleDescriptor.newBuilder().setId(id))
            // A trip ID the static timetable lacks, as in today's live feed.
            .setTrip(TripDescriptor.newBuilder().setRouteId(route).setTripId("live-" + id))
            .setPosition(position)
            .setTimestamp(at);
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

  List<String> names(List<StandService.Stand> found) {
    return found.stream().map(StandService.Stand::name).toList();
  }

  // ── Search ──────────────────────────────────────────────────────────────

  @Test
  void namesAreComparedWithoutCaseSpacingOrSpellingVariants() {
    assertEquals("rohini sec 21", StandService.normalize("Rohini Sec21"));
    assertEquals("rohini sec 21", StandService.normalize("ROHINI  SECTOR-21"));
    assertEquals("isbt kashmere gate", StandService.normalize("ISBT Kashmiri Gate"));
    assertEquals("", StandService.normalize(" / "));
  }

  @Test
  void sameNameStopsCloseTogetherAreOneSuggestion() {
    var found = stands.search("azadpur terminal", 10);
    // b1 + b2 merged; "far" has the same name but is 5 km away.
    assertEquals(2, found.size());
    var main = found.stream().filter(s -> s.stopIds().size() == 2).findFirst().orElseThrow();
    assertEquals(Set.of("b1", "b2"), Set.copyOf(main.stopIds()));
    assertEquals("Azadpur Terminal", main.name()); // not the all-caps spelling
    assertEquals(List.of("100", "200"), main.routeNumbers());
    assertEquals(2, main.routeCount());
  }

  @Test
  void namePartsInEitherOrderAreOneStandAndSameNamedPlacesAreToldApart() {
    stop("p1", "Rohini Sec18 Police Chowki / Rohini Sec 18", LAT + 0.02, 77.135);
    stop("p2", "Rohini Sec 18 / Rohini Sec18 Police Chowki", LAT + 0.0202, 77.135);
    var chowki = stands.search("police chowki", 10);
    assertEquals(1, chowki.size());
    assertEquals(Set.of("p1", "p2"), Set.copyOf(chowki.get(0).stopIds()));
    assertNull(chowki.get(0).hint());

    // The two "Azadpur Terminal" stands, 5 km apart, each get a landmark.
    stop("lm", "Mukundpur Chowk", LAT + 0.046, 77.110);
    var both = stands.search("azadpur terminal", 10);
    assertEquals(2, both.size());
    assertTrue(both.stream().allMatch(s -> s.hint() != null && s.hint().startsWith("near ")), both.toString());
    assertNotEquals(both.get(0).hint(), both.get(1).hint());
  }

  @Test
  void partialAndCaseInsensitiveNamesMatchWithBestMatchFirst() {
    var found = names(stands.search("AZAD", 10));
    assertEquals(3, found.size()); // the merged stand, the far one, the metro stop
    assertTrue(found.containsAll(List.of("Azadpur Terminal", "Azadpur Metro Station")));

    // Exact name before longer names that start with it.
    assertEquals("Azadpur Terminal", stands.search("azadpur terminal", 10).get(0).name());
    // Words in any order, each a prefix.
    assertEquals(List.of("Azadpur Metro Station"), names(stands.search("metro azad", 10)));
  }

  @Test
  void spellingVariantsAndPartlyTypedWordsStillMatch() {
    assertEquals(
        Set.of("ISBT Kashmiri Gate", "Kashmere Gate City Bus Terminal"),
        Set.copyOf(names(stands.search("Kashmere Gate", 10))));
    // "sect" can only be the start of "sector", which DTC writes "Sec".
    assertEquals(List.of("Rohini Sec 18 B7 Road"), names(stands.search("rohini sect", 10)));
    assertEquals(List.of("Rohini Sec 18 B7 Road"), names(stands.search("Rohini Sector 18", 10)));
  }

  @Test
  void unknownOrTooShortQueriesFindNothing() {
    assertTrue(stands.search("xyzzy nagar", 10).isEmpty());
    assertTrue(stands.search("a", 10).isEmpty());
    assertTrue(stands.search("  ", 10).isEmpty());
    assertEquals(1, stands.search("azad", 1).size());
  }

  // ── Live arrivals ───────────────────────────────────────────────────────

  @Test
  void liveBusesHeadingToTheStandAreListedSoonestFirstWithDestinationAndStatus() {
    feed(
        bus("near", "r100", 77.105, 30, 90), // ~490 m west of b1, eastbound
        bus("further", "r100", 77.1005, 20, 90), // ~930 m west of b1
        bus("passed", "r100", 77.115, 30, 90), // already past the stand
        bus("other", "r300", 77.112, 30, 90)); // route does not call here

    var result = stands.arrivals("b2", Instant.ofEpochSecond(now));
    assertEquals("Azadpur Terminal", result.stopName());
    assertEquals(Set.of("b1", "b2"), new HashSet<>(result.stops().stream().map(StandService.StandStop::id).toList()));
    assertEquals(List.of("near", "further"), result.arrivals().stream().map(StandService.BusArrival::vehicleId).toList());

    var near = result.arrivals().get(0);
    assertEquals("100", near.routeNumber());
    assertEquals("Kashmere Gate", near.destination());
    assertEquals("b1", near.stopId());
    assertEquals(StandService.SOURCE_LIVE, near.dataSource());
    assertEquals(StandService.DISTANCE_ALONG_ROUTE, near.distanceBasis());
    assertEquals(0.49, near.distanceKm(), 0.02);
    assertEquals(0.98, near.etaMinutes(), 0.05); // 490 m at 30 km/h
    assertEquals("ARRIVING", near.status());
    assertEquals(LAT, near.busLatitude(), 1e-4);
    assertEquals(now, near.lastGpsUpdate());

    var further = result.arrivals().get(1);
    assertEquals("APPROACHING", further.status());
    assertTrue(further.etaMinutes() > near.etaMinutes());

    assertEquals("LIVE", result.liveState());
    assertNull(result.notice());
    assertEquals(2, result.summary().liveBusCount());
    assertEquals(2, result.summary().approachingCount());
    assertEquals("near", result.summary().nextArrival().vehicleId());
  }

  @Test
  void etaFollowsTheLatestGpsPosition() {
    feed(bus("b", "r100", 77.1005, 20, 90));
    double before = stands.arrivals("b1", Instant.ofEpochSecond(now)).arrivals().get(0).etaMinutes();
    feed(bus("b", "r100", 77.106, 20, 90)); // moved ~540 m closer
    var after = stands.arrivals("b1", Instant.ofEpochSecond(now)).arrivals().get(0);
    assertTrue(after.etaMinutes() < before, after.etaMinutes() + " should be < " + before);
    assertEquals(0.39, after.distanceKm(), 0.02);
  }

  @Test
  void aBusIsListedOnceEvenWhenItCallsAtTwoStopsOfTheStand() {
    // Route 400 calls at both sides of the stand.
    route("r400", "400", "t400", 36000, "a", "b1", "b2", "c");
    feed(bus("loop", "r400", 77.105, 30, 90));
    var arrivals = stands.arrivals("b1", Instant.ofEpochSecond(now)).arrivals();
    assertEquals(1, arrivals.stream().filter(a -> a.vehicleId().equals("loop")).count());
  }

  @Test
  void aBusEndingItsTripAtTheStandIsListedButNotCountedAsComing() {
    route("r500", "500", "t500", 36000, "a", "b1"); // terminates at the stand
    feed(bus("ends", "r500", 77.106, 30, 90), bus("goes", "r100", 77.1005, 20, 90));
    var result = stands.arrivals("b1", Instant.ofEpochSecond(now));
    var ends = result.arrivals().stream().filter(a -> a.vehicleId().equals("ends")).findFirst().orElseThrow();
    assertTrue(ends.terminatesHere());
    assertFalse(result.arrivals().stream().filter(a -> a.vehicleId().equals("goes")).findFirst().orElseThrow().terminatesHere());
    assertEquals(2, result.summary().liveBusCount());
    assertEquals(1, result.summary().approachingCount());
    assertEquals("goes", result.summary().nextArrival().vehicleId());
  }

  @Test
  void withoutALiveBusTheTimetableIsShownAndLabelledScheduled() {
    var at = Instant.ofEpochSecond(now);
    int nowSod = at.atZone(StandService.SERVICE_ZONE).toLocalTime().toSecondOfDay();
    // 100 is due at b1 in 15 min; its trip starts at "a" 10 minutes earlier.
    index.trips.clear();
    index.routesAtStop.clear();
    route("r100", "100", "t100", Math.floorMod(nowSod + 300, 86400), "a", "b1", "c");
    route("r200", "200", "t200", Math.floorMod(nowSod + 7200, 86400), "c", "b2", "a"); // beyond 1 h
    feed(bus("other", "r300", 77.112, 30, 90)); // feed is up, nothing heading here

    var result = stands.arrivals("b1", at);
    assertTrue(result.arrivals().isEmpty());
    assertEquals("NO_LIVE_BUSES", result.liveState());
    assertEquals(StandService.SCHEDULED_NOTICE, result.notice());
    assertEquals(1, result.scheduled().size());
    var s = result.scheduled().get(0);
    assertEquals("100", s.routeNumber());
    assertEquals("b1", s.stopId());
    assertEquals("Kashmere Gate", s.destination());
    assertEquals(StandService.SOURCE_SCHEDULED, s.dataSource());
    assertEquals(15, s.minutesUntil(), 0.1);
    assertEquals(s, result.summary().nextScheduled());
    assertNull(result.summary().nextArrival());
  }

  @Test
  void aRouteWithALiveBusIsNotAlsoListedFromTheTimetable() {
    var at = Instant.ofEpochSecond(now);
    int nowSod = at.atZone(StandService.SERVICE_ZONE).toLocalTime().toSecondOfDay();
    index.trips.clear();
    index.routesAtStop.clear();
    route("r100", "100", "t100", Math.floorMod(nowSod + 300, 86400), "a", "b1", "c");
    feed(bus("near", "r100", 77.105, 30, 90));
    var result = stands.arrivals("b1", at);
    assertEquals(1, result.arrivals().size());
    assertTrue(result.scheduled().isEmpty());
    assertNull(result.notice());
  }

  @Test
  void aTripsLastStopIsNotABoardableDeparture() {
    var at = Instant.ofEpochSecond(now);
    int nowSod = at.atZone(StandService.SERVICE_ZONE).toLocalTime().toSecondOfDay();
    index.trips.clear();
    index.routesAtStop.clear();
    route("r100", "100", "t100", Math.floorMod(nowSod + 300, 86400), "a", "b1"); // ends at b1
    assertTrue(stands.arrivals("b1", at).scheduled().isEmpty());
  }

  @Test
  void statusRules() {
    assertEquals(StandService.Status.ARRIVING, StandService.status(5.0, 250.0, 20.0, 20, null).status());
    assertEquals(StandService.Status.ARRIVING, StandService.status(1.5, 800.0, 20.0, 20, null).status());
    assertEquals(StandService.Status.APPROACHING, StandService.status(6.0, 2000.0, 20.0, 20, null).status());
    assertEquals(StandService.Status.ON_THE_WAY, StandService.status(18.0, 6000.0, 20.0, 20, null).status());
    assertEquals(StandService.Status.ON_THE_WAY, StandService.status(null, 6000.0, null, 20, null).status());
    // Lagging GPS and running late are DELAYED, with the reason spelled out.
    var lagging = StandService.status(6.0, 2000.0, 20.0, 100, null);
    assertEquals(StandService.Status.DELAYED, lagging.status());
    assertTrue(lagging.reason().contains("100 s"));
    var late = StandService.status(8.0, 2000.0, 20.0, 20, 7.0);
    assertEquals(StandService.Status.DELAYED, late.status());
    assertTrue(late.reason().contains("7 min behind"));
    assertTrue(StandService.status(6.0, 2000.0, 0.0, 20, null).reason().startsWith("Stopped"));
  }

  @Test
  void unknownStopIsNotFound() {
    var e = assertThrows(ResponseStatusException.class, () -> stands.arrivals("nope", Instant.now()));
    assertEquals(404, e.getStatusCode().value());
  }

  @Test
  void publishedDestinationIsTheTextAfterTo() {
    assertEquals("Azadpur Terminal", TransitIndex.destinationOf("Rohini Sec 22 Terminal to Azadpur Terminal"));
    assertEquals("New Delhi Railway Station Gate 2",
        TransitIndex.destinationOf("MAHADEV APARTMENTS SECTOR 23B, DWARKA A to New Delhi Railway Station Gate 2"));
    assertNull(TransitIndex.destinationOf("Ring Road Seva"));
  }
}
