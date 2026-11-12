package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.google.transit.realtime.GtfsRealtime.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

/** /buses/regional answers are built once per realtime snapshot and shared by every poll. */
class RegionalBusesCacheTest {
  static final double[] NW = {28.72, 77.05}, N = {28.70, 77.22};

  TransitIndex index;
  RealtimeSnapshot snapshot;
  RegionService regions;
  RegionalBusesCache cache;
  JsonMapper json = JsonMapper.builder().build();
  AtomicInteger builds = new AtomicInteger();
  volatile CountDownLatch gate;
  long now;

  @BeforeEach
  void setup() {
    now = Instant.now().getEpochSecond();
    index = new TransitIndex();
    snapshot = new RealtimeSnapshot();
    var transit = new LiveTransitService(index, snapshot);
    regions =
        new RegionService(index, transit, snapshot, new RouteLearner(index, transit)) {
          @Override
          public RegionalBuses buses(Filter filter, String routeNumber, boolean insideAreaOnly) {
            builds.incrementAndGet();
            var g = gate;
            if (g != null) {
              try {
                g.await(5, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            return super.buses(filter, routeNumber, insideAreaOnly);
          }
        };
    index.addStop(new TransitIndex.Stop("nw", "Rohini", NW[0], NW[1]));
    index.addStop(new TransitIndex.Stop("n", "Civil Lines", N[0], N[1]));
    index.routes.put("rD", new TransitIndex.Route("rD", "300", "300UP", "UP", true));
    index.trips.put(
        "tD",
        new TransitIndex.Trip(
            "tD", "rD", "0", "",
            List.of(new TransitIndex.Visit("nw", 1, 36000, 36000), new TransitIndex.Visit("n", 2, 36600, 36600))));
    index.routesAtStop.computeIfAbsent("nw", k -> new HashSet<>()).add("rD");
    index.routesAtStop.computeIfAbsent("n", k -> new HashSet<>()).add("rD");
    index.computeRegions();
    cache = new RegionalBusesCache(regions, snapshot, json);
  }

  @AfterEach
  void stop() {
    cache.shutdown();
  }

  void feed(double lat, double lng, long stamp) {
    var v =
        VehiclePosition.newBuilder()
            .setVehicle(VehicleDescriptor.newBuilder().setId("bus300"))
            .setTrip(TripDescriptor.newBuilder().setRouteId("rD").setTripId("tD"))
            .setPosition(Position.newBuilder().setLatitude((float) lat).setLongitude((float) lng).setSpeed(5))
            .setTimestamp(stamp);
    snapshot.accept(
        FeedMessage.newBuilder()
            .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(stamp))
            .addEntity(FeedEntity.newBuilder().setId("1").setVehicle(v))
            .build(),
        true,
        false);
  }

  RegionService.RegionalBuses decode(byte[] body) {
    return json.readValue(body, RegionService.RegionalBuses.class);
  }

  /** Waits for the warm-up the feed update queued, so counts below are deterministic. */
  void awaitWarmUp() throws Exception {
    for (int i = 0; i < 200 && builds.get() == 0; i++) Thread.sleep(10);
    Thread.sleep(50);
  }

  @Test
  void pollsOfOneSnapshotShareOneBuild() throws Exception {
    feed(NW[0], NW[1], now);
    awaitWarmUp();
    int afterWarmUp = builds.get();
    assertEquals(1, afterWarmUp, "the default view is built right after the feed update");
    var first = cache.get(RegionService.Filter.ANY, "", true);
    var second = cache.get(RegionService.Filter.ANY, "", true);
    assertSame(first, second);
    assertEquals(afterWarmUp, builds.get());
    assertEquals("bus300", decode(first.json()).buses().getFirst().vehicleId());
  }

  @Test
  void newSnapshotIsServedInsteadOfTheCachedOne() throws Exception {
    feed(NW[0], NW[1], now - 20);
    awaitWarmUp();
    var before = cache.get(RegionService.Filter.ANY, "", true);
    feed(N[0], N[1], now);
    var after = cache.get(RegionService.Filter.ANY, "", true);
    assertNotEquals(before.etag(), after.etag());
    var bus = decode(after.json()).buses().getFirst();
    assertEquals(now, bus.lastUpdatedEpochSeconds());
    assertEquals(N[0], bus.busLatitude(), 1e-5);
  }

  @Test
  void gzipBodyIsTheSameAnswerAsTheJsonBody() throws Exception {
    feed(NW[0], NW[1], now);
    var e = cache.get(RegionService.Filter.ANY, "", true);
    byte[] unzipped = new GZIPInputStream(new ByteArrayInputStream(e.gzip())).readAllBytes();
    assertArrayEquals(e.json(), unzipped);
    assertTrue(e.gzip().length < e.json().length);
    // Same content as serialising the service's answer directly.
    assertEquals(json.readTree(json.writeValueAsBytes(regions.buses(RegionService.Filter.ANY, "", true))),
        json.readTree(e.json()));
  }

  @Test
  void concurrentMissesWaitForOneBuild() throws Exception {
    feed(NW[0], NW[1], now);
    awaitWarmUp();
    builds.set(0);
    gate = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<RegionalBusesCache.Encoded>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) results.add(pool.submit(() -> cache.get(RegionService.Filter.ANY, "300", true)));
      Thread.sleep(200);
      gate.countDown();
      var first = results.getFirst().get(5, TimeUnit.SECONDS);
      for (var r : results) assertSame(first, r.get(5, TimeUnit.SECONDS));
      assertEquals(1, builds.get());
    } finally {
      gate = null;
      pool.shutdownNow();
    }
  }

  @Test
  void agedAnswerOfTheSameSnapshotIsServedWhileItRebuilds() throws Exception {
    feed(NW[0], NW[1], now);
    awaitWarmUp();
    var first = cache.get(RegionService.Filter.ANY, "", true);
    cache.maxAgeMs = 50;
    Thread.sleep(80); // `first` is now past its age, still of the current snapshot
    builds.set(0);
    gate = new CountDownLatch(1); // the background rebuild blocks until released
    long t = System.nanoTime();
    assertSame(first, cache.get(RegionService.Filter.ANY, "", true), "no new positions: no waiting");
    assertTrue((System.nanoTime() - t) / 1_000_000 < 1000);
    cache.maxAgeMs = 60_000; // the rebuilt answer stays fresh however slow this machine is
    gate.countDown();
    for (int i = 0; i < 100 && cache.get(RegionService.Filter.ANY, "", true) == first; i++) Thread.sleep(10);
    assertNotSame(first, cache.get(RegionService.Filter.ANY, "", true));
    assertEquals(1, builds.get(), "one background rebuild");
    gate = null;
  }

  @Test
  void routeNumberCaseAndSpacesShareAnEntry() {
    feed(NW[0], NW[1], now);
    assertSame(cache.get(RegionService.Filter.ANY, " 300 ", true), cache.get(RegionService.Filter.ANY, "300", true));
  }

  @Test
  void nextUpdateFallsBackToTheDefaultUntilTheCadenceIsKnown() {
    assertEquals(RegionalBusesCache.DEFAULT_NEXT_MS, cache.nextUpdateInMs());
  }

  @Test
  void positionsAreSentWithFiveDecimalPlaces() {
    feed(28.7234567, 77.0512345, now);
    var bus = decode(cache.get(RegionService.Filter.ANY, "", true).json()).buses().getFirst();
    // Five places are ~1.1 m: the value is the feed's position to within half of that.
    assertEquals((float) 28.7234567, bus.busLatitude(), 5.1e-6);
    assertEquals((float) 77.0512345, bus.busLongitude(), 5.1e-6);
    assertEquals(bus.busLatitude(), RegionService.round(bus.busLatitude(), 5));
    assertEquals(bus.busLongitude(), RegionService.round(bus.busLongitude(), 5));
  }

  @Test
  void nullFieldsAreLeftOutOfTheJson() {
    feed(NW[0], NW[1], now);
    var bus = json.readTree(cache.get(RegionService.Filter.ANY, "", true).json()).get("buses").get(0);
    assertEquals("bus300", bus.get("vehicleId").asString());
    assertFalse(bus.has("distanceFromShapeMeters"), "no shape for this trip: the key is omitted");
    assertTrue(bus.has("likelyRoutes"), "empty lists are still sent");
    bus.properties().forEach(p -> assertFalse(p.getValue().isNull(), p.getKey() + " is null"));
  }

  /** Rebuilds the per-bus objects from the compact columns, the way the web client does. */
  List<Map<String, Object>> expand(tools.jackson.databind.JsonNode c) {
    var cols = c.get("cols");
    long fetchedAt = c.get("fetchedAt").asLong();
    List<Map<String, Object>> out = new ArrayList<>();
    for (int i = 0; i < c.get("count").asInt(); i++) {
      Map<String, Object> b = new TreeMap<>();
      for (String col : RegionalBusesCache.COLUMNS) {
        var v = cols.get(col).get(i);
        if (v.isNull()) continue;
        switch (col) {
          case "lat" -> b.put("busLatitude", v.asLong() / 100000.0);
          case "lng" -> b.put("busLongitude", v.asLong() / 100000.0);
          case "age" -> b.put("lastUpdatedEpochSeconds", (int) (fetchedAt - v.asLong())); // Integer, as Jackson reads it
          case "offRouteFlag" -> b.put("offRouteFlag", v.asInt() == 1);
          case "tripId" -> {
            String trip = v.asString();
            if (c.get("tripIdPrefixed").asBoolean() && trip.startsWith("^"))
              trip = cols.get("routeId").get(i).asString() + trip.substring(1);
            b.put("tripId", trip);
          }
          default -> b.put(col, json.treeToValue(v, Object.class));
        }
      }
      out.add(b);
    }
    if ("vehicleId".equals(c.get("order").asString()))
      out.sort(Comparator.comparing(b -> (String) b.get("vehicleId")));
    return out;
  }

  @Test
  void compactFormCarriesTheSameBusesAndFields() throws Exception {
    // Three buses fed out of vehicleId order, one on a second route, so the regrouping is exercised.
    index.routes.put("rE", new TransitIndex.Route("rE", "301", "301UP", "UP", true));
    index.routesAtStop.get("nw").add("rE");
    index.computeRegions();
    var feed = FeedMessage.newBuilder()
        .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now));
    String[][] fleet = {{"busC", "rD", "tD"}, {"busA", "rE", "rE_9"}, {"busB", "rD", "other"}};
    for (var f : fleet)
      feed.addEntity(FeedEntity.newBuilder().setId(f[0]).setVehicle(
          VehiclePosition.newBuilder()
              .setVehicle(VehicleDescriptor.newBuilder().setId(f[0]))
              .setTrip(TripDescriptor.newBuilder().setRouteId(f[1]).setTripId(f[2]))
              .setPosition(Position.newBuilder().setLatitude((float) NW[0]).setLongitude((float) NW[1]))
              .setTimestamp(now - 7)));
    snapshot.accept(feed.build(), true, false);

    var e = cache.get(RegionService.Filter.ANY, "", true);
    var plain = json.readTree(e.json());
    var compact = json.readTree(new GZIPInputStream(new ByteArrayInputStream(e.compactGzip())).readAllBytes());
    assertEquals(3, plain.get("buses").size());
    assertEquals(3, compact.get("count").asInt());

    List<Map<String, Object>> expected = new ArrayList<>();
    for (var b : plain.get("buses")) expected.add(new TreeMap<>(json.treeToValue(b, Map.class)));
    assertEquals(expected, expand(compact), "same buses, same fields, same order");
    for (String field : List.of("warnings", "fetchedAt", "feedAvailable", "region", "eligibleRouteCount",
        "excludedByRegion", "excludedOutsideArea"))
      assertEquals(plain.get(field), compact.get(field), field);
    assertNotEquals(e.etag(), e.compactEtag());
    assertTrue(e.matches(e.etag()) && e.matches(e.compactEtag()) && e.matches(e.etag().replace("\"", "")));
    assertFalse(e.matches("") || e.matches(null) || e.matches("\"other\""));
  }

  @Test
  void aWaitingClientGetsTheNextDifferentAnswer() throws Exception {
    feed(NW[0], NW[1], now - 20);
    awaitWarmUp();
    var held = cache.get(RegionService.Filter.ANY, "", true);
    var next = new CompletableFuture<RegionalBusesCache.Encoded>();
    cache.await(RegionService.Filter.ANY, "", true, held.etag(), next::complete);
    assertEquals(1, cache.waiting());
    Thread.sleep(100);
    assertFalse(next.isDone(), "nothing new yet");

    feed(N[0], N[1], now); // the warm-up build of the new snapshot releases the waiter
    var got = next.get(5, TimeUnit.SECONDS);
    assertNotEquals(held.etag(), got.etag());
    assertEquals(now, decode(got.json()).buses().getFirst().lastUpdatedEpochSeconds());
    assertEquals(0, cache.waiting());
  }

  @Test
  void aClientHoldingAnOldAnswerIsAnsweredAtOnceAndCanWithdraw() throws Exception {
    feed(NW[0], NW[1], now);
    awaitWarmUp();
    var current = cache.get(RegionService.Filter.ANY, "", true);
    var next = new CompletableFuture<RegionalBusesCache.Encoded>();
    cache.await(RegionService.Filter.ANY, "", true, "\"stale\"", next::complete);
    assertSame(current, next.get(1, TimeUnit.SECONDS));
    assertEquals(0, cache.waiting());

    Runnable withdraw = cache.await(RegionService.Filter.ANY, "", true, current.compactEtag(), x -> fail("withdrawn"));
    assertEquals(1, cache.waiting());
    withdraw.run();
    assertEquals(0, cache.waiting());
    feed(N[0], N[1], now);
    awaitWarmUp();
  }

  @Test
  void aWaitedRouteViewIsRebuiltWithTheSnapshot() throws Exception {
    feed(NW[0], NW[1], now - 20);
    awaitWarmUp();
    var held = cache.get(RegionService.Filter.ANY, "300", false);
    var next = new CompletableFuture<RegionalBusesCache.Encoded>();
    cache.await(RegionService.Filter.ANY, "300", false, held.etag(), next::complete);
    feed(N[0], N[1], now); // nobody polls the route view: the feed update itself rebuilds it
    assertEquals(now, decode(next.get(5, TimeUnit.SECONDS).json()).buses().getFirst().lastUpdatedEpochSeconds());
  }
}
