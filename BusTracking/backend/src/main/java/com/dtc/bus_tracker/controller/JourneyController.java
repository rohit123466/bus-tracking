package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.JourneyPlanRequest;
import com.dtc.bus_tracker.dto.JourneyPlanResponse;
import com.dtc.bus_tracker.service.JourneyPlannerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/journey")
@RequiredArgsConstructor
public class JourneyController {

    private final JourneyPlannerService journeyPlannerService;

    /**
     * POST /api/journey/plan — Smart Journey Planner.
     *
     * <p>Optional {@code preference} query parameter:
     * <ul>
     *   <li>{@code FASTEST} (default) — sort by total journey minutes</li>
     *   <li>{@code FEWEST_TRANSFERS} — prefer direct routes; sort by leg count then time</li>
     *   <li>{@code MINIMUM_WALKING} — sort by total walking distance then time</li>
     * </ul>
     * Preserves the existing API contract: calling without {@code preference} behaves as before.
     */
    @PostMapping("/plan")
    public ResponseEntity<JourneyPlanResponse> plan(
            @Valid @RequestBody JourneyPlanRequest request,
            @RequestParam(defaultValue = "FASTEST") String preference) {
        JourneyPlannerService.JourneyPreference pref;
        try {
            pref = JourneyPlannerService.JourneyPreference.valueOf(preference.toUpperCase());
        } catch (IllegalArgumentException e) {
            pref = JourneyPlannerService.JourneyPreference.FASTEST;
        }
        return ResponseEntity.ok(journeyPlannerService.plan(
                request.getSourceLat(), request.getSourceLng(),
                request.getDestinationLat(), request.getDestinationLng(), pref));
    }
}
