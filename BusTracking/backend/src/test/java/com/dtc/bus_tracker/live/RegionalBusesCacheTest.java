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
  void positionsAreRoundedToTheFeedsPrecisionOnly() {
    feed(28.7234567, 77.0512345, now);
    var bus = decode(cache.get(RegionService.Filter.ANY, "", true).json()).buses().getFirst();
    // The feed is float32: the rounded value is that float to within 1e-6 degrees (~0.1 m).
    assertEquals((float) 28.7234567, bus.busLatitude(), 1e-6);
    assertEquals((float) 77.0512345, bus.busLongitude(), 1e-6);
    assertEquals(bus.busLatitude(), RegionService.round(bus.busLatitude(), 6));
  }
}
