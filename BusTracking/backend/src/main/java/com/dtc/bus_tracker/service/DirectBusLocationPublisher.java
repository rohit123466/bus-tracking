package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Writes ingested events straight to the Redis-backed {@link BusLocationStore}
 * via {@link BusLocationIngestService}.
 */
@Service
@RequiredArgsConstructor
public class DirectBusLocationPublisher implements BusLocationPublisher {

    private final BusLocationIngestService ingestService;

    @Override
    public void publish(BusLocationEvent event) {
        ingestService.ingest(event);
    }

    @Override
    public void publishAll(java.util.List<BusLocationEvent> events) {
        ingestService.ingestAll(events);
    }
}
