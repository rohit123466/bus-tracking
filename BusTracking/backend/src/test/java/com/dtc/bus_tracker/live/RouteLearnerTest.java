package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Learning a route's real path, stops and travel times from simulated live GPS. */
class RouteLearnerTest {
  // A road running due north along 77.1000; one poll every 10 s, 0.001 deg (~111 m) per poll.
  static final double LNG = 77.1;
  static final long T0 = 1_800_000_000L;

  TransitIndex index;
  LiveTransitService transit;
  RouteLearner learner;

  @BeforeEach
  void setup() {
    index = new TransitIndex();
    transit = new LiveTransitService(index, new RealtimeSnapshot());
    learner = new RouteLearner(index, transit);
    index.routes.put("rL", new TransitIndex.Route("rL", "500", "500UP", "UP", true));
    index.addStop(new TransitIndex.Stop("L1", "First", 28.705, LNG + 0.0002)); // ~20 m east
    index.addStop(new TransitIndex.Stop("L2", "Second", 28.735, LNG - 0.0001));
    index.addStop(new TransitIndex.Stop("OFF", "Parallel road", 28.72, LNG + 0.01)); // ~1 km away
  }

  static RealtimeSnapshot.Vehicle at(String id, String trip, double lat, double lng, long time) {
    return new RealtimeSnapshot.Vehicle(id, "rL", trip, "0", null, lat, lng, 0.0, null, time);
  }

  /** Waits 60 s at the terminal (GPS jitter only), then drives 28.700 -> 28.740. */
  void drive(String id, String trip, long start) {
    for (int i = 0; i < 6; i++)
      learner.observe(List.of(at(id, trip, 28.7 + (i % 2) * 0.00005, LNG, start + i * 10)), start + i * 10);
    long t = start + 60;
    for (int i = 1; i <= 40; i++, t += 10)
      learner.observe(List.of(at(id, trip, 28.7 + i * 0.001, LNG, t)), t);
  }

  @Test
  void learnsTheDrivenPathItsStopsInOrderAndTravelTimes() {
    drive("bus1", "trip1", T0);
    var route = learner.route("rL");
    assertNotNull(route, "a route is usable while its first drive is still in progress");
    assertEquals(4400, route.lengthMeters(), 300);
    assertEquals(List.of("L1", "L2"), route.stops().stream().map(RouteLearner.LearnedStop::stopId).toList());
    // The 60 s terminal wait is not travel time: L1 is 5 polls (50 s) into the drive.
    assertEquals(50, route.stop("L1").seconds(), 6);
    assertEquals(350, route.stop("L2").seconds(), 6);
    assertNull(route.stop("OFF"));
  }

  @Test
  void busPositionIsLocatedAlongThePath() {
    drive("bus1", "trip1", T0);
    var route = learner.route("rL");
    var on = route.locate(28.72, LNG + 0.0003); // ~30 m off the road
    assertTrue(on.offset() < 40);
    assertEquals(2220, on.along(), 150);
    assertEquals(0, on.bearing(), 5, "path heads north");
    assertTrue(route.locate(28.72, LNG + 0.02).offset() > RouteLearner.MAX_BUS_OFFSET_METERS);
    assertEquals(1, route.stopsBetween(0, route.stop("L2").along()));
  }

  @Test
  void gpsGlitchEndsTheRecordingInsteadOfDrawingAJump() {
    for (int i = 0; i <= 15; i++)
      learner.observe(List.of(at("bus1", "t", 28.7 + i * 0.001, LNG, T0 + i * 10)), T0 + i * 10);
    // 5 km jump in 10 s, then a short drive there.
    for (int i = 0; i <= 3; i++)
      learner.observe(List.of(at("bus1", "t", 28.80 + i * 0.001, LNG, T0 + 200 + i * 10)), T0 + 200 + i * 10);
    var route = learner.route("rL");
    assertEquals(1665, route.lengthMeters(), 150, "the 1.6 km drive is kept, the jump is not");
  }

  @Test
  void newTripStartsANewRecordingAndLongerDriveWins() {
    for (int i = 0; i <= 15; i++)
      learner.observe(List.of(at("bus1", "a", 28.7 + i * 0.001, LNG, T0 + i * 10)), T0 + i * 10);
    double first = learner.route("rL").lengthMeters();
    drive("bus1", "b", T0 + 1000); // same bus, next trip, longer
    assertTrue(learner.route("rL").lengthMeters() > first + 2000);
  }

  @Test
  void unknownRoutesAreNotLearned() {
    learner.observe(List.of(new RealtimeSnapshot.Vehicle(
        "x", "nope", "t", "0", null, 28.7, LNG, 0.0, null, T0)), T0);
    learner.observe(List.of(new RealtimeSnapshot.Vehicle(
        "x", "nope", "t", "0", null, 28.72, LNG, 0.0, null, T0 + 100)), T0 + 100);
    assertTrue(learner.routes().isEmpty());
  }

  @Test
  void learnedPathsSurviveARestart(@TempDir Path dir) {
    learner.file = dir.resolve("learned.txt").toString();
    drive("bus1", "trip1", T0);
    learner.save();

    var restarted = new RouteLearner(index, transit);
    restarted.file = learner.file;
    restarted.load();
    var route = restarted.route("rL");
    assertNotNull(route);
    assertEquals(learner.route("rL").lengthMeters(), route.lengthMeters(), 1);
    assertEquals(350, route.stop("L2").seconds(), 6);
  }
}
