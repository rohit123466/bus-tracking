package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.BusOccupancySummary;
import com.dtc.bus_tracker.dto.CameraObservationRequest;
import com.dtc.bus_tracker.dto.CameraPresetRequest;
import com.dtc.bus_tracker.dto.CameraReport;
import com.dtc.bus_tracker.dto.LiveOccupancyResponse;
import com.dtc.bus_tracker.dto.PassengerDto;
import com.dtc.bus_tracker.dto.SellTicketRequest;
import com.dtc.bus_tracker.dto.StandingFrameRequest;
import com.dtc.bus_tracker.dto.TicketResponse;
import com.dtc.bus_tracker.service.CameraMonitoringService;
import com.dtc.bus_tracker.service.OccupancyDashboardService;
import com.dtc.bus_tracker.service.StandingAreaMonitor;
import com.dtc.bus_tracker.service.StandingZoneTracker;
import com.dtc.bus_tracker.service.TicketService;
import com.dtc.bus_tracker.service.TicketingDemoDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Conductor / bus operator console. Requires the same JWT as the admin
 * dashboard (see SecurityConfig): selling tickets and editing camera data
 * are operator actions, unlike the public seat map.
 */
@RestController
@RequestMapping("/api/conductor")
@RequiredArgsConstructor
public class ConductorController {

    private final TicketService ticketService;
    private final CameraMonitoringService cameraMonitoringService;
    private final OccupancyDashboardService occupancyDashboardService;
    private final TicketingDemoDataService demoDataService;
    private final StandingAreaMonitor standingAreaMonitor;

    @GetMapping("/passengers")
    public ResponseEntity<List<PassengerDto>> getPassengers() {
        return ResponseEntity.ok(ticketService.passengers());
    }

    @GetMapping("/buses/{busNumber}/tickets")
    public ResponseEntity<List<TicketResponse>> getTickets(@PathVariable String busNumber) {
        return ResponseEntity.ok(ticketService.ticketsForBus(busNumber));
    }

    // POST /api/conductor/tickets - sell a seat for a route segment.
    @PostMapping("/tickets")
    // Resubmitting the same requestId returns the original ticket (200) instead of selling twice.
    public ResponseEntity<TicketResponse> sellTicket(@Valid @RequestBody SellTicketRequest request, Principal principal) {
        TicketResponse ticket = ticketService.sell(request, principal.getName());
        if (ticket.isReplayed()) {
            return ResponseEntity.ok(ticket);
        }
        // Logged next to the camera events; a sale never changes the standing count.
        standingAreaMonitor.note(ticket.getBusNumber(), "TICKET_ISSUED", "Ticket #" + ticket.getTicketNumber()
                + " issued by " + principal.getName() + " - seat " + ticket.getSeatNumber() + ", "
                + ticket.getFromStop() + " → " + ticket.getToStop() + ".");
        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    // Live console view: trip, seats, standing area, seat availability, ticket sales.
    @GetMapping("/buses/{busNumber}/live")
    public ResponseEntity<LiveOccupancyResponse> getLive(@PathVariable String busNumber, Principal principal) {
        return ResponseEntity.ok(occupancyDashboardService.live(busNumber, principal.getName()));
    }

    // A camera / IoT device pushes one standing-area frame.
    @PostMapping("/buses/{busNumber}/camera/standing-frames")
    public ResponseEntity<Map<String, Object>> reportStandingFrame(@PathVariable String busNumber,
                                                                   @Valid @RequestBody StandingFrameRequest request,
                                                                   Principal principal) {
        StandingZoneTracker.FrameResult result = cameraMonitoringService.recordStandingFrame(busNumber, request);
        return ResponseEntity.ok(Map.of("result", result,
                "live", occupancyDashboardService.live(busNumber, principal.getName())));
    }

    // Choose the standing-area preset played on this bus (null pauses the preset feed).
    @PutMapping("/buses/{busNumber}/camera/preset")
    public ResponseEntity<LiveOccupancyResponse> selectCameraPreset(@PathVariable String busNumber,
                                                                    @Valid @RequestBody CameraPresetRequest request,
                                                                    Principal principal) {
        cameraMonitoringService.selectPreset(busNumber, request.getPresetId());
        return ResponseEntity.ok(occupancyDashboardService.live(busNumber, principal.getName()));
    }

    // Passenger got down: frees the seat from atStopId (default: the bus's current stop).
    @PostMapping("/tickets/{ticketNumber}/complete")
    public ResponseEntity<TicketResponse> completeTicket(@PathVariable String ticketNumber,
                                                         @RequestParam(required = false) Long atStopId) {
        return ResponseEntity.ok(ticketService.complete(ticketNumber, atStopId));
    }

    @PostMapping("/tickets/{ticketNumber}/cancel")
    public ResponseEntity<TicketResponse> cancelTicket(@PathVariable String ticketNumber) {
        return ResponseEntity.ok(ticketService.cancel(ticketNumber));
    }

    // Move the bus to its next stop; passengers whose destination it is get down.
    @PostMapping("/buses/{busNumber}/advance")
    public ResponseEntity<Map<String, Object>> advanceBus(@PathVariable String busNumber) {
        List<TicketResponse> alighted = ticketService.advance(busNumber);
        BusOccupancySummary summary = occupancyDashboardService.summary(busNumber);
        return ResponseEntity.ok(Map.of("bus", summary, "alighted", alighted));
    }

    @GetMapping("/buses/{busNumber}/camera")
    public ResponseEntity<CameraReport> getCamera(@PathVariable String busNumber) {
        return ResponseEntity.ok(cameraMonitoringService.report(busNumber));
    }

    // Run a seat scan - see SimulatedCameraOccupancyProvider.
    @PostMapping("/buses/{busNumber}/camera/scan")
    public ResponseEntity<CameraReport> scanCamera(@PathVariable String busNumber) {
        return ResponseEntity.ok(cameraMonitoringService.scan(busNumber));
    }

    // Push seat observations (manual correction today, a real CV service later).
    @PostMapping("/buses/{busNumber}/camera/observations")
    public ResponseEntity<CameraReport> reportObservations(@PathVariable String busNumber,
                                                           @Valid @RequestBody CameraObservationRequest request) {
        return ResponseEntity.ok(cameraMonitoringService.recordObservations(busNumber, request));
    }

    // Restore the deterministic preset tickets/passengers/camera data.
    @PostMapping("/demo/reset")
    public ResponseEntity<List<BusOccupancySummary>> resetDemo() {
        demoDataService.reset();
        return ResponseEntity.ok(occupancyDashboardService.summaries());
    }
}
