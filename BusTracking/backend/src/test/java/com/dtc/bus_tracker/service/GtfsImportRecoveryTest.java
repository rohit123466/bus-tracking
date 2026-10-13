package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.live.TransitIndex;
import com.dtc.bus_tracker.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:import-recovery;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop", "gtfs.import.enabled=false", "gtfs.import.radius-degrees=0", "dtc.api.key="})
@ActiveProfiles("demo")
class GtfsImportRecoveryTest {
  @Autowired GtfsImportService importer;
  @Autowired RouteRepository routes;
  @Autowired com.dtc.bus_tracker.repository.StopTimeRepository times;
  @MockitoBean TransitIndex index;
  @MockitoBean DtcApiClient client;
  @MockitoBean TicketingDemoDataService demo;

  @Test void failedImportRollsBackFlushedBatchesAndCanRetry() throws Exception {
    ReflectionTestUtils.setField(importer, "archive", new ByteArrayResource(archive(true)));
    assertThrows(Exception.class, importer::importAll);
    assertEquals(0, routes.count());
    assertEquals(0, times.count());
    ReflectionTestUtils.setField(importer, "archive", new ByteArrayResource(archive(false)));
    importer.importAll();
    assertEquals(1, routes.count());
    assertEquals(1001, times.count());
    importer.importAll();
    assertEquals(1001, times.count());
  }

  private byte[] archive(boolean malformed) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      entry(zip,"routes.txt","agency_id,route_id,route_short_name,route_long_name\na,r,534,534UP\n");
      entry(zip,"stops.txt","stop_code,stop_id,stop_lat,stop_lon,stop_name\nx,s,28.6,77.2,Source\n");
      entry(zip,"trips.txt","route_id,service_id,trip_id,shape_id\nr,service,t,\n");
      var rows = new StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n");
      for(int i=1;i<=1001;i++) rows.append("t,10:00:00,10:00:00,s,").append(i).append('\n');
      if(malformed) rows.append("\"unterminated");
      entry(zip,"stop_times.txt",rows.toString());
    }
    return bytes.toByteArray();
  }
  private void entry(ZipOutputStream zip,String name,String data) throws Exception {
    zip.putNextEntry(new ZipEntry(name));zip.write(data.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
  }
}
