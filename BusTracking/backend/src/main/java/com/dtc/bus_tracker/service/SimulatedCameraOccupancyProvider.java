package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.repository.CameraOccupancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * DEMO camera. There is no camera feed or computer-vision model in this
 * project, so a "scan" starts from ticket-based occupancy at the bus's
 * current stop and flips a small, configurable share of seats
 * (ticketing.camera.simulated-mismatch-rate) to mimic a passenger without a
 * ticket or a ticket holder who is standing / got down early. The random
 * generator is seeded from the bus id and scan number, so a given scan is
 * reproducible.
 */
@Service
@RequiredArgsConstructor
public class SimulatedCameraOccupancyProvider implements CameraOccupancyProvider {

    public static final String SOURCE = "SIMULATED";

    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final CameraOccupancyRepository cameraOccupancyRepository;
    private final TicketingProperties properties;

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
}
