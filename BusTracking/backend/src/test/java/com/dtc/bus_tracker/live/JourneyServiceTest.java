package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.*;

class JourneyServiceTest {
  TransitIndex index;
  JourneyService service;

  @BeforeEach
  void setup() {
    index = new TransitIndex();
    stop("a1", "Alpha", 28.700, 77.100);
    stop("a2", "Bravo", 28.705, 77.100);
    stop("x", "Interchange", 28.710, 77.100);
    stop("x2", "Interchange", 28.7101, 77.1001); // other side of the road, ~15 m away
    stop("b1", "Charlie", 28.720, 77.100);
    stop("b2", "Delta", 28.730, 77.100);
    index.routes.put("r101", new TransitIndex.Route("r101", "101", "101UP", "UP", false));
    index.routes.put("r202", new TransitIndex.Route("r202", "202", "202UP", "UP", false));
    trip("t1", "r101", "a1", 36000, "a2", 36300, "x", 36600);
    trip("t2", "r202", "x2", 37000, "b1", 37600, "b2", 38200);
    // A shorter trip on the same route must not replace the longest pattern.
    trip("t1short", "r101", "a1", 40000, "a2", 40300);
    service = new JourneyService(index);
  }

  void stop(String id, String name, double lat, double lng) {
    index.addStop(new TransitIndex.Stop(id, name, lat, lng));
  }

  void trip(String id, String route, Object... visits) {
    List<TransitIndex.Visit> list = new ArrayList<>();
    for (int i = 0; i < visits.length; i += 2) {
      String stop = (String) visits[i];
      int time = (Integer) visits[i + 1];
      list.add(new TransitIndex.Visit(stop, i / 2 + 1, time, time));
      index.routesAtStop.computeIfAbsent(stop, k -> new HashSet<>()).add(route);
    }
    index.trips.put(id, new TransitIndex.Trip(id, route, "", "", list));
  }

  @Test
  void directJourneyUsesScheduledRideTime() {
    var plan = service.plan(28.6995, 77.100, 28.7105, 77.100, "FASTEST");
    assertEquals("OK", plan.status());
    var best = plan.options().get(0);
    assertTrue(best.direct());
    var bus = best.segments().get(1);
    assertEquals("101", bus.routeNumber());
    assertEquals("SCHEDULED_GTFS", bus.basis());
    assertEquals("Interchange", bus.towards());
    assertEquals(bus.stops().size() - 1, bus.stopCount());
    assertEquals("WALK", best.segments().get(0).type());
    assertEquals("STRAIGHT_LINE", best.segments().get(0).basis());
  }

  @Test
  void transferAtNearbyStopAddsWalkingSegment() {
    var plan = service.plan(28.6995, 77.100, 28.7305, 77.100, "FASTEST");
    assertEquals("OK", plan.status());
    var journey = plan.options().get(0);
    assertFalse(journey.direct());
    assertEquals(1, journey.transfers());
    var types = journey.segments().stream().map(JourneyService.Segment::type).toList();
    assertEquals(List.of("WALK", "BUS", "WALK", "BUS", "WALK"), types);
    assertEquals("x", journey.segments().get(1).to().id());
    assertEquals("x2", journey.segments().get(3).from().id());
    // 10 min on 101 (a1 -> x) plus 20 min on 202 (x2 -> b2), straight from stop_times.
    assertEquals(30.0, journey.rideMinutes(), 0.01);
  }

  @Test
  void wrongDirectionIsNotOffered() {
    var plan = service.plan(28.7305, 77.100, 28.6995, 77.100, "FASTEST");
    assertEquals("NO_CONNECTION", plan.status());
    assertTrue(plan.options().isEmpty());
  }

  @Test
  void farFromAnyStopIsReported() {
    var plan = service.plan(28.50, 77.30, 28.7305, 77.100, "FASTEST");
    assertEquals("NO_STOP_NEAR_START", plan.status());
  }

  @Test
  void patternsAreOrderedAndNameTheirTerminus() {
    var patterns = service.patterns("101");
    assertEquals(1, patterns.size());
    var stops = patterns.get(0).stops().stream().map(TransitIndex.Stop::id).toList();
    assertEquals(List.of("a1", "a2", "x"), stops);
    assertEquals("Interchange", patterns.get(0).towards());
    assertTrue(service.patterns("999").isEmpty());
  }
}
