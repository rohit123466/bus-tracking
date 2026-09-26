package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.CameraObservationRequest;
import com.dtc.bus_tracker.dto.CameraReport;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.CameraOccupancy;
import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.exception.TicketingException;
import com.dtc.bus_tracker.repository.CameraOccupancyRepository;
import com.dtc.bus_tracker.service.CameraOccupancyProvider.SeatObservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Camera Occupancy Monitoring": stores camera scans and compares them with
 * ticket-based occupancy at the bus's current stop. The two are deliberately
 * independent - a ticket says who paid for a seat on this hop, the camera
 * says whether someone is physically sitting there - and every seat where
 * they disagree is reported as an occupancy mismatch.
 */
@Service
@RequiredArgsConstructor
public class CameraMonitoringService {

    public static final String SIMULATED_DISCLAIMER = "DEMO: camera observations are simulated from ticket data "
            + "with injected noise. No real camera feed or computer-vision model is connected.";

    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final CameraOccupancyProvider provider;
    private final CameraOccupancyRepository cameraOccupancyRepository;

    @Transactional(readOnly = true)
    public CameraReport report(String busNumber) {
        return report(journeyLoader.load(busNumber));
    }

    /** "Simulate Camera Scan": asks the provider for a fresh scan and stores it. */
    @Transactional
    public CameraReport scan(String busNumber) {
        TicketingJourney journey = journeyLoader.load(busNumber);
        requireCamera(journey.bus());
        List<SeatObservation> observations = provider.scan(journey.bus(), journey.seats());
        storeScan(journey, observations, provider.sourceName());
        return report(journeyLoader.load(busNumber));
    }

    /**
     * Observations pushed from outside (a conductor correcting the view, or a
     * real CV service). Stored as a new scan: listed seats take the reported
     * value, other seats carry over from the previous scan.
     */
    @Transactional
    public CameraReport recordObservations(String busNumber, CameraObservationRequest request) {
        TicketingJourney journey = journeyLoader.load(busNumber);
        requireCamera(journey.bus());

        Map<Long, SeatObservation> merged = new LinkedHashMap<>();
        Map<Long, String> sources = new HashMap<>();
        for (CameraOccupancy previous : latestScan(journey.bus())) {
            merged.put(previous.getSeat().getId(),
                    new SeatObservation(previous.getSeat(), previous.getStatus(), previous.getConfidence()));
            sources.put(previous.getSeat().getId(), previous.getSource());
        }
        String source = request.getSource() == null || request.getSource().isBlank()
                ? "MANUAL" : request.getSource().trim().toUpperCase();
        for (CameraObservationRequest.Observation o : request.getObservations()) {
            String number = TicketService.normalizeSeatNumber(o.getSeatNumber());
            Seat seat = journey.seats().stream()
                    .filter(s -> s.getSeatNumber().equalsIgnoreCase(number))
                    .findFirst()
                    .orElseThrow(() -> TicketingException.badRequest("Seat " + o.getSeatNumber().trim()
                            + " does not belong to bus " + journey.busNumber() + "."));
            merged.put(seat.getId(), new SeatObservation(seat, o.getStatus(),
                    o.getConfidence() != null ? o.getConfidence() : 1.0));
            sources.put(seat.getId(), source);
        }

        int scanNumber = cameraOccupancyRepository.findLatestScanNumber(journey.bus().getId()) + 1;
        LocalDateTime now = LocalDateTime.now();
        cameraOccupancyRepository.saveAll(merged.values().stream()
                .map(o -> row(journey, o, scanNumber, now, sources.get(o.seat().getId())))
                .toList());
        return report(journeyLoader.load(busNumber));
    }

    /** Persists one full scan at the bus's current stop. */
    public void storeScan(TicketingJourney journey, List<SeatObservation> observations, String source) {
        int scanNumber = cameraOccupancyRepository.findLatestScanNumber(journey.bus().getId()) + 1;
        LocalDateTime now = LocalDateTime.now();
        cameraOccupancyRepository.saveAll(observations.stream()
                .map(o -> row(journey, o, scanNumber, now, source))
                .toList());
    }

    public CameraReport report(TicketingJourney journey) {
        Bus bus = journey.bus();
        List<CameraOccupancy> scan = new ArrayList<>(latestScan(bus));
        scan.sort(Comparator.comparing(c -> c.getSeat().getId()));
        Map<Long, Ticket> onBoard = seatAvailability.onBoardAt(journey, journey.currentSequence());
        String currentStop = journey.stopName(journey.currentSequence());

        List<CameraReport.SeatObservation> observations = new ArrayList<>();
        List<CameraReport.Mismatch> mismatches = new ArrayList<>();
        int cameraOccupied = 0;
        for (CameraOccupancy c : scan) {
            Seat seat = c.getSeat();
            Ticket ticket = onBoard.get(seat.getId());
            CameraSeatStatus ticketStatus = ticket != null ? CameraSeatStatus.OCCUPIED : CameraSeatStatus.EMPTY;
            boolean mismatch = ticketStatus != c.getStatus();
            if (c.getStatus() == CameraSeatStatus.OCCUPIED) cameraOccupied++;

            if (mismatch) {
                String place = TicketService.label(seat);
                mismatches.add(ticket != null
                        ? CameraReport.Mismatch.builder()
                            .seatNumber(seat.getSeatNumber())
                            .type("TICKETED_BUT_EMPTY")
                            .message("Occupancy mismatch detected: ticket #" + ticket.getTicketNumber() + " says "
                                    + place + " is occupied (" + ticket.getFromStop().getName() + " → "
                                    + ticket.getToStop().getName() + "), but the camera sees it empty.")
                            .build()
                        : CameraReport.Mismatch.builder()
                            .seatNumber(seat.getSeatNumber())
                            .type("OCCUPIED_WITHOUT_TICKET")
                            .message("Occupancy mismatch detected: the camera sees " + place
                                    + " occupied, but no ticket covers it at " + currentStop + ".")
                            .build());
            }
            observations.add(CameraReport.SeatObservation.builder()
                    .seatNumber(seat.getSeatNumber())
                    .seatType(seat.getSeatType())
                    .cameraStatus(c.getStatus())
                    .confidence(c.getConfidence())
                    .source(c.getSource())
                    .detectedAt(c.getDetectedAt())
                    .ticketStatus(ticketStatus)
                    .ticketNumber(ticket != null ? ticket.getTicketNumber() : null)
                    .mismatch(mismatch)
                    .build());
        }

        Integer scanStop = scan.isEmpty() ? null : scan.getFirst().getStopSequence();
        return CameraReport.builder()
                .busNumber(journey.busNumber())
                .cameraInstalled(Boolean.TRUE.equals(bus.getCameraInstalled()))
                .cameraStatus(provider.feedStatus(bus))
                .provider(provider.sourceName())
                .simulated(provider.isSimulated())
                .disclaimer(provider.isSimulated() ? SIMULATED_DISCLAIMER : null)
                .scanNumber(scan.isEmpty() ? 0 : scan.getFirst().getScanNumber())
                .lastScanAt(scan.stream().map(CameraOccupancy::getDetectedAt).max(Comparator.naturalOrder()).orElse(null))
                .scanTakenAtStop(scanStop != null ? journey.stopName(scanStop) : null)
                .currentStop(currentStop)
                .scanStale(scanStop != null && scanStop != journey.currentSequence())
                .cameraOccupied(cameraOccupied)
                .cameraEmpty(scan.size() - cameraOccupied)
                .ticketOccupied(onBoard.size())
                .observations(observations)
                .mismatches(mismatches)
                .build();
    }

    private List<CameraOccupancy> latestScan(Bus bus) {
        int latest = cameraOccupancyRepository.findLatestScanNumber(bus.getId());
        return latest == 0 ? List.of() : cameraOccupancyRepository.findByBus_IdAndScanNumber(bus.getId(), latest);
    }

    private CameraOccupancy row(TicketingJourney journey, SeatObservation o, int scanNumber, LocalDateTime at, String source) {
        return CameraOccupancy.builder()
                .bus(journey.bus())
                .seat(o.seat())
                .scanNumber(scanNumber)
                .stopSequence(journey.currentSequence())
                .status(o.status())
                .confidence(o.confidence())
                .detectedAt(at)
                .source(source)
                .build();
    }

    private void requireCamera(Bus bus) {
        if (!Boolean.TRUE.equals(bus.getCameraInstalled())) {
            throw TicketingException.conflict("Bus " + bus.getVehicleId() + " has no occupancy camera installed.");
        }
    }
}
