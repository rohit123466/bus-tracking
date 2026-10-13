package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

class WalkingServiceTest {
  HttpServer server;
  TransitIndex index;
  WalkingService walking;

  @BeforeEach
  void setup() throws Exception {
    index = new TransitIndex();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      entry(zip, "routes.txt", "route_id,route_short_name,route_long_name\nr,534,534UP\n");
      entry(
          zip,
          "stops.txt",
          "stop_id,stop_name,stop_lat,stop_lon\ns,Near,28.601,77.2\nd,Reachable,28.602,77.2\n");
      entry(zip, "trips.txt", "trip_id,route_id\nt,r\n");
      entry(
          zip,
          "stop_times.txt",
          "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n"
              + "t,s,1,25:00:00,25:00:00\n"
              + "t,d,2,25:05:00,25:05:00\n");
    }
    ReflectionTestUtils.setField(index, "archive", new ByteArrayResource(bytes.toByteArray()));
    index.load();
    walking = new WalkingService(index);
    ReflectionTestUtils.setField(walking, "key", "test-only");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ReflectionTestUtils.setField(
        walking,
        "url",
        "http://127.0.0.1:"
            + server.getAddress().getPort()
            + "/v2/directions/foot-walking/geojson");
  }

  void entry(ZipOutputStream z, String name, String text) throws Exception {
    z.putNextEntry(new ZipEntry(name));
    z.write(text.getBytes(StandardCharsets.UTF_8));
    z.closeEntry();
  }

  @AfterEach
  void close() {
    server.stop(0);
  }

  @Test
  void nearestUsesWalkingDistanceAndReturnsProviderInstructions() {
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          boolean nearer = body.contains("28.601");
          String response =
              "{\"features\":[{\"geometry\":{\"coordinates\":[[77.2,28.6],[77.2,"
                  + (nearer ? "28.601" : "28.602")
                  + "]]},\"properties\":{\"summary\":{\"distance\":"
                  + (nearer ? 900 : 300)
                  + ",\"duration\":240},\"segments\":[{\"steps\":[{\"instruction\":\"Walk"
                  + " north\",\"distance\":300,\"way_points\":[0,1]}]}]}}]}";
          byte[] data = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, data.length);
          exchange.getResponseBody().write(data);
          exchange.close();
        });
    server.start();
    var result = walking.nearest(28.6, 77.2);
    assertTrue(result.available());
    assertEquals("d", result.stop().id());
    assertEquals("Walk north", result.maneuvers().get(0).instruction());
  }

  @Test
  void routingFailuresReturnLabeledFallback() {
    server.createContext(
        "/",
        e -> {
          e.sendResponseHeaders(503, -1);
          e.close();
        });
    server.start();
    var result = walking.route(28.6, 77.2, index.stops.get("s"));
    assertFalse(result.available());
    assertNull(result.durationSeconds());
    assertTrue(result.maneuvers().isEmpty());
  }

  @Test
  void staticIndexIncludesFullFeedAndOvernightTimes() {
    assertEquals(2, index.nearby(28.6, 77.2, 1000).size());
    assertEquals(90000, index.trips.get("t").visits().get(0).arrival());
  }

  @Test void malformedProviderMetricsCannotBecomeZeroMinuteWalk() {
    server.createContext("/", e -> {
      byte[] body = ("{\"features\":[{\"geometry\":{\"coordinates\":[[77.2,28.6],[77.2,28.601]]},"
          + "\"properties\":{\"summary\":{\"distance\":120,\"duration\":\"invalid\"}}}]}")
          .getBytes(StandardCharsets.UTF_8);
      e.sendResponseHeaders(200, body.length);
      e.getResponseBody().write(body);
      e.close();
    });
    server.start();
    var result = walking.route(28.6, 77.2, index.stops.get("s"));
    assertFalse(result.available());
    assertNull(result.durationSeconds());
    assertTrue(result.maneuvers().isEmpty());
  }

  @Test void replacementArchiveLoadsShapesBySequenceAndRejectsInvalidGeometry() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      entry(zip, "routes.txt", "route_long_name,route_id,route_short_name\n534UP,r,534\n");
      entry(zip, "stops.txt", "stop_id,stop_name,stop_lat,stop_lon\ns,Stop,28.6,77.2\nbad,Invalid,NaN,77.2\n");
      entry(zip, "trips.txt", "trip_id,route_id,shape_id,direction_id\nt,r,valid,0\norphan,missing,invalid,1\n");
      entry(zip, "stop_times.txt", "trip_id,stop_id,stop_sequence\nt,s,1\n");
      entry(zip, "shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
          + "valid,28.601,77.2,2\nvalid,28.6,77.2,1\ninvalid,NaN,77.2,1\ninvalid,28.6,77.2,2\n");
    }
    var replacement = new TransitIndex();
    ReflectionTestUtils.setField(replacement, "archive", new ByteArrayResource(bytes.toByteArray()));
    replacement.load();
    assertEquals(1, replacement.stops.size());
    assertEquals(1, replacement.trips.size());
    assertEquals(28.6, replacement.shapes.get("valid").getFirst().lat());
    assertFalse(replacement.shapes.containsKey("invalid"));
    assertEquals(-1, replacement.trips.get("t").visits().getFirst().arrival());
  }
}
