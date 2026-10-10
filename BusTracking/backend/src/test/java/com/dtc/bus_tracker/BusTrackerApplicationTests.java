package com.dtc.bus_tracker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.dtc.bus_tracker.service.GtfsImportService;
import com.dtc.bus_tracker.service.DtcApiClient;
import com.dtc.bus_tracker.live.TransitIndex;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:contexttest;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "dtc.api.key=",
    "spring.h2.console.enabled=false"
})
@ActiveProfiles("demo")
class BusTrackerApplicationTests {
    // Context wiring is tested without external feeds or a multi-million-row import.
    @MockitoBean GtfsImportService gtfsImportService;
    @MockitoBean DtcApiClient dtcApiClient;
    @MockitoBean TransitIndex transitIndex;
    @Test void contextLoads() {}
}
