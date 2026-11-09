package com.dtc.bus_tracker.config;

import com.dtc.bus_tracker.service.GtfsImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Seeds GTFS after the application is ready so deployment health checks do not
 * wait for a potentially large database import.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "gtfs.import.enabled", havingValue = "true", matchIfMissing = true)
public class GtfsImportStartup {

    private final GtfsImportService gtfsImportService;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void importAfterStartup() {
        try {
            gtfsImportService.importAll();
            log.info("GTFS startup import completed.");
        } catch (Exception ex) {
            log.error("GTFS startup import failed. The API remains available, but transit data may be incomplete.",
                    ex);
        }
    }
}
