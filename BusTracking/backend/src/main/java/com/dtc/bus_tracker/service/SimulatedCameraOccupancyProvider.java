package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.exception.TicketingException;
import com.dtc.bus_tracker.repository.CameraOccupancyRepository;
import com.dtc.bus_tracker.service.CameraPresetLibrary.FrameSpec;
import com.dtc.bus_tracker.service.CameraPresetLibrary.Preset;
import com.dtc.bus_tracker.service.StandingZoneTracker.Frame;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preset camera. There is no camera feed or computer-vision model in this
 * project, so:
 * <ul>
 *   <li>a seat "scan" starts from ticket-based occupancy at the bus's current
 *       stop and flips a small, configurable share of seats
 *       (ticketing.camera.simulated-mismatch-rate) to mimic a passenger
 *       without a ticket or a ticket holder who got down early. The random
 *       generator is seeded from the bus id and scan number, so a given scan
 *       is reproducible;</li>
 *   <li>the standing-area feed plays the bus's selected preset from
 *       {@link CameraPresetLibrary} one frame per tick, looping.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SimulatedCameraOccupancyProvider implements CameraOccupancyProvider {

    public static final String SOURCE = "SIMULATED";

    /** Where one bus is in its preset. Guarded by its own monitor. */
    private static final class Cursor {
        Preset preset;
        int index;
        long sequence;
        Frame lastFrame;
    }

    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final CameraOccupancyRepository cameraOccupancyRepository;
    private final TicketingProperties properties;
    private final CameraPresetLibrary presetLibrary;

    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public boolean isSimulated() {
        return true;
    }

    @Override
    public String feedStatus(Bus bus) {
        return Boolean.TRUE.equals(bus.getCameraInstalled()) ? "CONNECTED" : "NOT_INSTALLED";
    }

    @Override
    public List<SeatObservation> scan(Bus bus, List<Seat> seats) {
        TicketingJourney journey = journeyLoader.load(bus, false);
        Set<Long> ticketed = seatAvailability.onBoardAt(journey, journey.currentSequence()).keySet();
        int scanNumber = cameraOccupancyRepository.findLatestScanNumber(bus.getId()) + 1;
        Random random = new Random(bus.getId() * 7919L + scanNumber);
        double mismatchRate = properties.getCamera().getSimulatedMismatchRate();

        List<SeatObservation> observations = new ArrayList<>();
        for (Seat seat : seats) {
            if (!Boolean.TRUE.equals(seat.getInService())) continue;
            boolean seenOccupied = ticketed.contains(seat.getId());
            boolean disagree = random.nextDouble() < mismatchRate;
            if (disagree) seenOccupied = !seenOccupied;
            double confidence = disagree ? 0.55 + random.nextDouble() * 0.25 : 0.86 + random.nextDouble() * 0.13;
            observations.add(new SeatObservation(seat,
                    seenOccupied ? CameraSeatStatus.OCCUPIED : CameraSeatStatus.EMPTY,
                    Math.round(confidence * 100) / 100.0));
        }
        return observations;
    }

    @Override
    public Optional<Frame> nextStandingFrame(String busNumber) {
        Cursor cursor = cursor(busNumber);
        synchronized (cursor) {
            if (cursor.preset == null) return Optional.empty();
            List<FrameSpec> frames = cursor.preset.frames();
            FrameSpec spec = frames.get(cursor.index);
            cursor.index = (cursor.index + 1) % frames.size();

            if (!spec.signal()) return Optional.empty();
            // A resent frame keeps its sequence number, exactly as a device
            // retransmitting after a missed acknowledgement would.
            if (spec.resend()) return Optional.ofNullable(cursor.lastFrame);
            cursor.lastFrame = new Frame(++cursor.sequence, Instant.now(), spec.detections(), SOURCE);
            return Optional.of(cursor.lastFrame);
        }
    }

    @Override
    public List<FeedPreset> presets() {
        return presetLibrary.all().stream()
                .map(p -> new FeedPreset(p.id(), p.label(), p.description()))
                .toList();
    }

    @Override
    public String activePreset(String busNumber) {
        Cursor cursor = cursor(busNumber);
        synchronized (cursor) {
            return cursor.preset != null ? cursor.preset.id() : null;
        }
    }

    @Override
    public void selectPreset(String busNumber, String presetId) {
        Preset preset = presetId == null || presetId.isBlank() ? null : presetLibrary.find(presetId.trim())
                .orElseThrow(() -> TicketingException.badRequest("Camera preset '" + presetId + "' does not exist."));
        Cursor cursor = cursor(busNumber);
        synchronized (cursor) {
            // The stream continues: only the scene changes, so sequence
            // numbers keep counting and the tracker's debounce still applies.
            cursor.preset = preset;
            cursor.index = 0;
        }
    }

    @Override
    public void resetFeed() {
        cursors.clear();
    }

    private Cursor cursor(String busNumber) {
        return cursors.computeIfAbsent(busNumber, k -> {
            Cursor cursor = new Cursor();
            cursor.preset = presetLibrary.defaultFor(busNumber).orElse(null);
            return cursor;
        });
    }
}
