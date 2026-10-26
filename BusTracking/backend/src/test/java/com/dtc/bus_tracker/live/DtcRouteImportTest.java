package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;

/** Reading DTC's route workbook and matching its stop names to GTFS stops. */
class DtcRouteImportTest {
  static Map<Integer, String> row(Object... colAndValue) {
    Map<Integer, String> r = new TreeMap<>();
    for (int i = 0; i < colAndValue.length; i += 2) r.put((Integer) colAndValue[i], (String) colAndValue[i + 1]);
    return r;
  }

  @Test
  void readsSideBySideUpAndDownBlocks() {
    // Layout of the "DTC Routes" sheet: C=serial, D=route, E=fare-stage mark, F=stop (UP); H..K (DN).
    var rows = List.of(
        row(2, "Fare Stage Sr No", 3, "Route 03 UP", 7, "Fare Stage Sr No", 8, "Route 03 DN"),
        row(2, "1", 3, "3", 4, "Fare Stage", 5, "Sarai Kale Khan ISBT", 8, "3", 9, "Fare Stage", 10, "Shalimar Bagh Desu Colony"),
        row(3, "3", 5, "Indraprastha Park", 8, "3", 10, "Shalimar Bagh BH-Block"),
        // The next route's header sits one column further left, over the same stop column: it
        // must still end the 03 UP block rather than add these stops to it.
        row(1, "Fare Stage Sr No", 2, "Route D-9909 (+) UP"),
        row(1, "1", 3, "D-9909 (+)", 4, "Fare Stage", 5, "Dwarka Sec-11 Metro Station"),
        row(3, "D-9909 (+)", 5, "Bensups Hospital"));
    var routes = DtcRouteImport.routesInSheet("DTC Routes", rows);
    assertEquals(3, routes.size());
    var up = routes.get(0);
    assertEquals("03", up.number());
    assertEquals("UP", up.direction());
    assertEquals(List.of("Sarai Kale Khan ISBT", "Indraprastha Park"),
        up.stops().stream().map(DtcRouteImport.OfficialStop::name).toList());
    assertTrue(up.stops().get(0).fareStage());
    assertFalse(up.stops().get(1).fareStage());
    assertEquals("DN", routes.get(1).direction());
    assertEquals(2, routes.get(1).stops().size());
    assertEquals("D-9909 (+)", routes.get(2).number());
    assertEquals(List.of("Dwarka Sec-11 Metro Station", "Bensups Hospital"),
        routes.get(2).stops().stream().map(DtcRouteImport.OfficialStop::name).toList());
  }

  @Test
  void readsAWorkbookFile() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      put(zip, "xl/workbook.xml", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
          + "<sheets><sheet name=\"DTC Routes\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      put(zip, "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
      put(zip, "xl/sharedStrings.xml", "<sst><si><t>Route 106 UP</t></si><si><t>Fare Stage</t></si>"
          + "<si><t>Old Delhi Railway Station</t></si><si><r><t>Tis Hazari </t></r><r><t>Animal Hospital</t></r></si></sst>");
      put(zip, "xl/worksheets/sheet1.xml", "<worksheet><sheetData>"
          + "<row r=\"1\"><c r=\"D1\" t=\"s\"><v>0</v></c></row>"
          + "<row r=\"2\"><c r=\"D2\"><v>106</v></c><c r=\"E2\" t=\"s\"><v>1</v></c><c r=\"F2\" t=\"s\"><v>2</v></c></row>"
          + "<row r=\"3\"><c r=\"D3\"><v>106</v></c><c r=\"F3\" t=\"s\"><v>3</v></c></row>"
          + "</sheetData></worksheet>");
    }
    var routes = DtcRouteImport.readWorkbook(new ByteArrayInputStream(bytes.toByteArray()));
    assertEquals(1, routes.size());
    assertEquals("106", routes.get(0).number());
    assertEquals(List.of("Old Delhi Railway Station", "Tis Hazari Animal Hospital"),
        routes.get(0).stops().stream().map(DtcRouteImport.OfficialStop::name).toList());
  }

  private static void put(ZipOutputStream zip, String name, String xml) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(xml.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  @Test
  void stopNamesAreComparedAfterNormalisingAbbreviationsAndTypos() {
    assertEquals(DtcRouteImport.tokens("Ashok Vihar Crossing"), DtcRouteImport.tokens("Ashok Vihar X-ing"));
    assertEquals(DtcRouteImport.tokens("LNJP Hospital"), DtcRouteImport.tokens("L.N.J.P Hospital"));
    assertEquals(DtcRouteImport.tokens("Rohini Sector 11"), DtcRouteImport.tokens("Rohini Sec.-11"));
    // Word order and a one-letter typo do not prevent a match.
    assertTrue(DtcRouteImport.similarity(
        DtcRouteImport.tokens("AE-BLK Shalimar Bagh"), DtcRouteImport.tokens("Shalmar Bagh AE Block")) > 0.9);
    // A different sector number is a different stop.
    assertTrue(DtcRouteImport.similarity(
        DtcRouteImport.tokens("Rohini Sec-16"), DtcRouteImport.tokens("Rohini Sec-17")) < DtcRouteImport.MIN_SCORE + 0.1);
  }

  @Test
  void sameNamedStopsArePickedByKeepingTheRouteContinuous() {
    // Route 106 around Clock Tower; another "Clock Tower" stop exists ~20 km away.
    var clock = stop("b", "Clock Tower", 28.6754, 77.2018);
    var farClock = stop("c", "Clock Tower", 28.5000, 77.3000);
    var birla = stop("d", "Birla Mill", 28.6789, 77.2005);
    var shakti = stop("a", "Shakti Nagar", 28.6804, 77.1993);
    var matcher = new DtcRouteImport.Matcher(List.of(farClock, shakti, clock, birla));
    var aligned = matcher.align(List.of(
        new DtcRouteImport.OfficialStop("Clock Tower", true),
        new DtcRouteImport.OfficialStop("Birla Mill", false),
        new DtcRouteImport.OfficialStop("Unknown Place", false),
        new DtcRouteImport.OfficialStop("Shakti Nagar", false)));
    assertEquals("b", aligned.get(0).stop().id()); // the Clock Tower beside the rest of the route
    assertEquals("d", aligned.get(1).stop().id());
    assertNull(aligned.get(2)); // no such GTFS stop: left unmatched
    assertEquals("a", aligned.get(3).stop().id());
  }

  @Test
  void aSameNamedStopOffTheRouteIsLeftUnmatched() {
    // Only a "Clock Tower" ~20 km away exists: matching it would be a 40 km detour.
    var matcher = new DtcRouteImport.Matcher(List.of(
        stop("a", "Roshnara Bagh", 28.6733, 77.2018), stop("c", "Clock Tower", 28.5000, 77.3000),
        stop("d", "Birla Mill", 28.6789, 77.2005)));
    var aligned = matcher.align(List.of(
        new DtcRouteImport.OfficialStop("Roshnara Bagh", false),
        new DtcRouteImport.OfficialStop("Clock Tower", true),
        new DtcRouteImport.OfficialStop("Birla Mill", false)));
    assertEquals("a", aligned.get(0).stop().id());
    assertNull(aligned.get(1));
    assertEquals("d", aligned.get(2).stop().id());
  }

  static DtcRouteImport.GtfsStop stop(String id, String name, double lat, double lng) {
    return new DtcRouteImport.GtfsStop(id, name, lat, lng, DtcRouteImport.tokens(name));
  }

  @Test
  void csvFieldsWithCommasAreQuotedAndReadBack() {
    String line = String.join(",", "1", DtcRouteImport.csv("L.N.J.P, Hospital"), DtcRouteImport.csv("say \"hi\""));
    assertEquals(List.of("1", "L.N.J.P, Hospital", "say \"hi\""), DtcRouteImport.parseCsvLine(line));
  }
}
