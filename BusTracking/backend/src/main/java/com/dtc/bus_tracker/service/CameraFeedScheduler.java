package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.repository.BusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Continuous standing-area verification: every feed interval, asks the
 * camera provider for each camera bus's next frame and hands it to
 * {@link StandingAreaMonitor}. A tick with no frame is simply skipped - the
 * monitor notices the silence itself and holds the last confirmed reading.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraFeedScheduler {

    private static final long BUS_LIST_TTL_MS = 30_000;

    private final TicketingProperties properties;
    private final BusRepository busRepository;
    private final CameraOccupancyProvider provider;
    private final StandingAreaMonitor monitor;

    private volatile List<String> cameraBuses = List.of();
    private volatile long cameraBusesLoadedAt;

    @Scheduled(fixedDelayString = "${ticketing.camera.feed-interval-ms:2000}", initialDelay = 5000)
    public void tick() {
        if (!properties.getCamera().isFeedEnabled()) return;
        try {
            for (String busNumber : cameraBuses()) {
                provider.nextStandingFrame(busNumber).ifPresent(frame -> monitor.accept(busNumber, frame));
            }
        } catch (Exception e) {
            log.warn("Standing-area feed tick failed: {}", e.getMessage());
        }
    }

    /** Camera buses change only when buses are seeded, so the list is cached briefly. */
    private List<String> cameraBuses() {
        long now = System.currentTimeMillis();
        if (cameraBuses.isEmpty() || now - cameraBusesLoadedAt > BUS_LIST_TTL_MS) {
            cameraBuses = busRepository.findByBusTypeIsNotNullOrderByVehicleIdAsc().stream()
                    .filter(bus -> Boolean.TRUE.equals(bus.getCameraInstalled()))
                    .map(Bus::getVehicleId)
                    .toList();
            cameraBusesLoadedAt = now;
        }
        return cameraBuses;
    }
}
