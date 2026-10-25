package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;

/** DTC's official route paths: on-route checks and recognising a route from a driven path. */
class OfficialRoutesTest {
  OfficialRoutes official;

  /** Points due north along {@code lng} from {@code fromLat} to {@code toLat}, ~550 m apart. */
  static List<TransitIndex.Point> north(double lng, double fromLat, double toLat) {
    List<TransitIndex.Point> out = new ArrayList<>();
    double step = Math.signum(toLat - fromLat) * 0.005;
    for (double lat = fromLat; step > 0 ? lat <= toLat + 1e-9 : lat >= toLat - 1e-9; lat += step)
      out.add(new TransitIndex.Point(lat, lng, out.size()));
    return out;
  }

  /** A drive sampled every ~110 m (one feed poll at ~40 km/h). */
  static List<TransitIndex.Point> drive(double lng, double fromLat, double toLat) {
    List<TransitIndex.Point> out = new ArrayList<>();
    double step = Math.signum(toLat - fromLat) * 0.001;
    for (double lat = fromLat; step > 0 ? lat <= toLat + 1e-9 : lat >= toLat - 1e-9; lat += step)
      out.add(new TransitIndex.Point(lat, lng, out.size()));
    return out;
  }

  @BeforeEach
  void setup() {
    official = new OfficialRoutes();
    official.add("427", "UP", north(77.10, 28.70, 28.74)); // 4.4 km north along 77.10
    official.add("427", "DN", north(77.10, 28.74, 28.70));
    // 990 shares 427's road for its first 3.3 km, then turns east.
    List<TransitIndex.Point> turning = new ArrayList<>(north(77.10, 28.70, 28.73));
    for (double lng = 77.105; lng <= 77.15; lng += 0.005) turning.add(new TransitIndex.Point(28.73, lng, turning.size()));
    official.add("990", "UP", turning);
    official.add("0GL-23 (NS)", "UP", north(77.30, 28.60, 28.65));
  }

  @Test
  void routeNumbersCompareWithoutSpacingLeadingZerosOrNightServiceMarks() {
    assertEquals(OfficialRoutes.key("3"), OfficialRoutes.key("Route 03".substring(6)));
    assertEquals(OfficialRoutes.key("39STL"), OfficialRoutes.key("39 STL"));
    assertEquals(OfficialRoutes.key("D7101"), OfficialRoutes.key("D-7101"));
    assertEquals(OfficialRoutes.key("130"), OfficialRoutes.key("No.130"));
    assertEquals(OfficialRoutes.key("GL-23"), OfficialRoutes.key("0GL-23 (NS)"));
    assertNotEquals(OfficialRoutes.key("106"), OfficialRoutes.key("106A"));
    assertEquals(1, official.paths("GL23").size());
    assertEquals("GL-23", official.paths("GL23").get(0).number());
  }

  @Test
  void busNearAnOfficialPathIsOnThatRoute() {
    assertTrue(official.onRoute("427", 28.715, 77.102)); // ~200 m off the line
    assertFalse(official.onRoute("427", 28.715, 77.13)); // ~3 km east
    assertFalse(official.onRoute("106", 28.715, 77.10)); // not in the list
  }

  @Test
  void aFullDriveRulesOutTheRouteThatSharesOnlyPartOfTheRoad() {
    var drive = drive(77.1003, 28.70, 28.74);
    assertEquals(List.of("427"), official.consistentNumbers(drive));
    var best = official.fits(drive).get(0);
    assertEquals("427", best.number());
    assertEquals("UP", best.direction());
    assertTrue(best.score() > 0.9);
  }

  @Test
  void driveTheOtherWayMatchesTheOtherDirection() {
    var drive = drive(77.1003, 28.74, 28.70);
    assertEquals(List.of("427"), official.consistentNumbers(drive));
    assertEquals("DN", official.fits(drive).get(0).direction());
  }

  @Test
  void driveOnlyOnTheSharedStretchFitsBothRoutes() {
    var shared = drive(77.1003, 28.70, 28.73);
    // Best first: the shared stretch is most of 427 but under half of 990.
    assertEquals(List.of("427", "990"), official.consistentNumbers(shared));
  }

  @Test
  void shortOrUnrelatedDrivesIdentifyNothing() {
    assertNull(official.consistentNumbers(drive(77.1003, 28.70, 28.71))); // ~1 km: too short
    assertTrue(official.consistentNumbers(drive(77.20, 28.70, 28.74)).isEmpty()); // elsewhere
  }

  @Test
  void readsTheImporterCsvSkippingUnmatchedStops() throws Exception {
    String csv = String.join("\n",
        "# DTC official route stops matched to GTFS stops. Generated 2026-10-25 from fare_stages.xlsx by DtcRouteImport.",
        "path,sheet,route,direction,seq,name,fare_stage,stop_id,lat,lng,score",
        "1,DTC Routes,03,UP,1,Sarai Kale Khan ISBT,1,10,28.589,77.256,1.00",
        "1,DTC Routes,03,UP,2,Nowhere,0,,,,",
        "1,DTC Routes,03,UP,3,\"L.N.J.P, Hospital\",0,11,28.637,77.236,0.90",
        "2,Diversion,03,UP,1,Only One Stop,1,12,28.70,77.10,1.00");
    var routes = new OfficialRoutes();
    routes.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    assertEquals(1, routes.size()); // path 2 has a single matched stop: no line to compare with
    var p = routes.paths("3").get(0);
    assertEquals("3", p.number());
    assertEquals(2, p.points().size());
    assertTrue(routes.source().contains("fare_stages.xlsx"));
  }
}
