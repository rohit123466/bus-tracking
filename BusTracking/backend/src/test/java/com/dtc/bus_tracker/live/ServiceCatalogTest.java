package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class ServiceCatalogTest {
  private ServiceCatalog catalog;

  @BeforeEach
  void load() throws Exception {
    var tables = Map.of(
        "master_routes_unified.csv",
        """
        master_route_id,master_route_key,canonical_route_code,service_category,agency_id,origin_terminal,destination_terminal,origin_stop_id,destination_stop_id,gtfs_up_route_id,gtfs_down_route_id,gtfs_up_trips_count,gtfs_down_trips_count,has_static_gtfs,has_fare_stages,fare_stage_sheets,express_route_ids,ds_route_id,night_route_ids,ncr_route_ids
        MR_0040,102A,102A,Regular DTC/Cluster Route,DOT,Rohini Sec 22 Terminal,Azadpur Terminal,1.0,7587.0,2.0,1.0,10,10,True,True,DTC Routes,,,,
        MR_0041,1020,1020,Regular DTC/Cluster Route,DTC,Narela Terminal,Azadpur Terminal,,,,,0,0,False,False,,,,,
        MR_0001,0114NS,0114(NS),Night Service (NS),DTC,Old Delhi Railway Station (T),Qutubgarh Village (Terminal),7572.0,418.0,1457.0,,1,0,True,False,,,,"NS_0114NS_UP_3",
        """,
        "fare_stage_routes_summary.csv",
        """
        category_sheet,route_code,direction,route_header,total_stops,total_fare_stages,origin_stop,destination_stop,total_distance_km,gtfs_route_id,gtfs_match_type,master_route_key,master_route_id
        DTC Routes,102A,UP,Route 102A UP,2,1,Azadpur Terminal,Rohini Sec 22 Terminal,12.5,2.0,EXACT,102A,MR_0040
        """,
        "fare_stages_stops.csv",
        """
        category_sheet,route_header,route_code,direction,stop_sequence,fare_stage_sr_no,is_fare_stage,stop_name,meter_reading_km,direction_distance_km,fare_stage_distance_km,gtfs_route_id,gtfs_match_type,gtfs_stop_id,stop_lat,stop_lon,master_route_id
        DTC Routes,Route 102A UP,102A,UP,1,1.0,True,Azadpur Terminal,,,,2.0,EXACT,7587.0,28.71,77.18,MR_0040
        DTC Routes,Route 102A UP,102A,UP,2,,False,Unmatched Stop,,,,2.0,EXACT,,,,MR_0040
        DTC Routes,Route 102A UP,102A,UP,1,1.0,True,Azadpur Terminal,,,,2.0,EXACT,7587.0,28.71,77.18,MR_0040
        DTC Routes,Route 102A UP,102A,UP,2,,False,Unmatched Stop,,,,2.0,EXACT,,,,MR_0040
        DTC Routes,Route 102A UP,102A,UP,1,1.0,True,Azadpur Terminal,,,,2.0,EXACT,7587.0,28.71,77.18,MR_0040
        DTC Routes,Route 102A UP,102A,UP,2,,False,Diverted Stop,,,,2.0,EXACT,,,,MR_0040
        """,
        "night_bus_routes.csv",
        """
        night_route_id,pdf_page,route_code,master_route_key,direction,origin_terminal,destination_terminal,scheduled_trip_count,departure_times_24h,service_note,gtfs_route_id,origin_stop_id,destination_stop_id,master_route_id
        NS_0114NS_UP_3,3,0114(NS),0114NS,UP,Old Delhi Railway Station (T),Qutubgarh Village (Terminal),2,"23:30, 00:15",Regular Night Service,1457,7572.0,418.0,MR_0001
        """,
        "night_bus_timetables.csv",
        """
        night_trip_id,night_route_id,pdf_page,route_code,direction,origin_terminal,destination_terminal,departure_time_hhmm,departure_time_24h,service_note,gtfs_route_id,origin_stop_id,destination_stop_id
        NS_0114NS_UP_3_T01,NS_0114NS_UP_3,3,0114(NS),UP,Old Delhi Railway Station (T),Qutubgarh Village (Terminal),2330,23:30,Regular Night Service,1457,7572.0,418.0
        NS_0114NS_UP_3_T02,NS_0114NS_UP_3,3,0114(NS),UP,Old Delhi Railway Station (T),Qutubgarh Village (Terminal),0015,00:15,Up to ITO,1457,7572.0,418.0
        """);
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      for (var t : tables.entrySet()) {
        zip.putNextEntry(new ZipEntry(t.getKey()));
        zip.write(t.getValue().getBytes(StandardCharsets.UTF_8));
      }
    }
    catalog = new ServiceCatalog(new ByteArrayResource(bytes.toByteArray(), "dtc-service-data.zip"));
    catalog.load();
  }

  @Test
  void findsRoutesByAnySpellingOfTheNumberOrTheMasterId() {
    assertEquals("MR_0040", catalog.route("102a").orElseThrow().id());
    assertEquals("MR_0040", catalog.route("MR_0040").orElseThrow().id());
    assertEquals("MR_0001", catalog.route("0114(NS)").orElseThrow().id());
    assertTrue(catalog.route("999").isEmpty());
  }

  @Test
  void floatFormattedIdsBecomeGtfsIds() {
    var route = catalog.route("102A").orElseThrow();
    assertEquals("2", route.upRouteId());
    assertEquals("1", route.downRouteId());
    assertNull(ServiceCatalog.id("nan"));
  }

  @Test
  void searchListsTheExactNumberFirst() {
    List<String> ids = catalog.routes("102", "", 10).stream().map(ServiceCatalog.MasterRoute::code).toList();
    assertEquals(List.of("1020", "102A"), ids);
    assertEquals("102A", catalog.routes("102A", "", 10).get(0).code());
    assertEquals(1, catalog.routes("rohini", "", 10).size());
    assertEquals(1, catalog.routes("", "night", 10).size());
  }

  @Test
  void fareStagesKeepStopOrderAndUnmatchedStops() {
    var stages = catalog.fareStages(catalog.route("102A").orElseThrow());
    // Three listings under one header: a repeat (dropped) and a different list (kept).
    assertEquals(2, stages.size());
    assertEquals("Diverted Stop", stages.get(1).stops().get(1).name());
    var stops = stages.get(0).stops();
    assertEquals(2, stops.size());
    assertEquals(12.5, stages.get(0).distanceKm());
    assertEquals("7587", stops.get(0).stopId());
    assertTrue(stops.get(0).fareStage());
    assertNull(stops.get(1).stopId());
    assertNull(stops.get(1).latitude());
  }

  @Test
  void nightServicesCarryTheirPublishedDepartures() {
    var night = catalog.services(catalog.route("0114NS").orElseThrow());
    assertEquals(1, night.size());
    assertEquals(ServiceCatalog.Kind.NIGHT, night.get(0).kind());
    assertEquals(List.of("1457"), night.get(0).gtfsRouteIds());
    assertEquals(List.of("23:30", "00:15"),
        night.get(0).departures().stream().map(ServiceCatalog.Departure::time).toList());
    assertEquals("Up to ITO", night.get(0).departures().get(1).note());
    assertEquals(1, catalog.summary().services().get(ServiceCatalog.Kind.NIGHT));
  }
}
