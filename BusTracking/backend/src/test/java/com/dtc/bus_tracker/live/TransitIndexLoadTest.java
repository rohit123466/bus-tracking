package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

/** GTFS archive loading: one pass when files are stored in dependency order, same result otherwise. */
class TransitIndexLoadTest {
  static final Map<String, String> FILES = new LinkedHashMap<>();

  static {
    // BOM on the first header, padded values, a short row and an unknown stop, as real feeds have.
    FILES.put("routes.txt", "﻿route_id,route_short_name,route_long_name\nr1, 534UP ,534UP\n");
    FILES.put("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\ns1,Azadpur,28.70,77.18\ns2, Mori Gate ,28.67,77.22\n");
    FILES.put("trips.txt", "route_id,trip_id,direction_id,shape_id\nr1,t1,0,sh1\nr1,t2\n");
    FILES.put(
        "stop_times.txt",
        "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n"
            + "t1,10:05:00,10:05:00,s2,2\nt1,10:00:00,10:00:00,s1,1\nt1,10:09:00,10:09:00,ghost,3\n"
            + "t2,25:10:00,25:10:00,s1,1\n");
    FILES.put(
        "shapes.txt",
        "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
            + "sh1,28.68,77.20,20\nsh1,28.70,77.18,10\nsh1,28.67,77.22,30\n");
  }

  static TransitIndex load(List<String> order) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (String name : order) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(FILES.get(name).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    var index = new TransitIndex();
    ReflectionTestUtils.setField(index, "archive", new ByteArrayResource(bytes.toByteArray()));
    index.load();
    return index;
  }

  static void assertLoaded(TransitIndex index) {
    assertEquals("534", index.routes.get("r1").number());
    assertEquals("Mori Gate", index.stops.get("s2").name());
    var visits = index.trips.get("t1").visits();
    assertEquals(List.of("s1", "s2"), visits.stream().map(TransitIndex.Visit::stopId).toList());
    assertEquals(36000, visits.getFirst().arrival());
    assertEquals(25 * 3600 + 600, index.trips.get("t2").visits().getFirst().arrival());
    assertEquals("", index.trips.get("t2").shapeId(), "missing trailing column reads as the default");
    assertEquals(Set.of("r1"), index.routesAtStop.get("s1"));
    var shape = index.shapes.get("sh1");
    assertEquals(List.of(10, 20, 30), shape.stream().map(TransitIndex.Point::sequence).toList());
    assertEquals(new TransitIndex.Point(28.70, 77.18, 10), shape.getFirst());
    assertNull(index.warning);
  }

  @Test
  void archiveInDependencyOrderLoadsInOnePass() throws Exception {
    assertLoaded(load(new ArrayList<>(FILES.keySet())));
  }

  @Test
  void archiveInAnotherOrderLoadsTheSame() throws Exception {
    assertLoaded(load(List.of("shapes.txt", "stop_times.txt", "trips.txt", "stops.txt", "routes.txt")));
  }

  @Test
  void archiveWithoutShapesStillLoads() throws Exception {
    var index = load(List.of("routes.txt", "stops.txt", "trips.txt", "stop_times.txt"));
    assertTrue(index.shapes.isEmpty());
    assertNotNull(index.warning);
    assertEquals(2, index.trips.get("t1").visits().size());
  }
}
