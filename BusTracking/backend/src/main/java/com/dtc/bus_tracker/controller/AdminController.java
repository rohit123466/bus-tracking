package com.dtc.bus_tracker.controller;

import com.dtc.bus_tracker.dto.AdminStatsResponse;
import com.dtc.bus_tracker.dto.FleetPageResponse;
import com.dtc.bus_tracker.service.AdminFleetService;
import com.dtc.bus_tracker.service.AdminStatsHistory;
import com.dtc.bus_tracker.service.AdminStatsService;
import com.dtc.bus_tracker.service.FeedHealthService;
import com.dtc.bus_tracker.service.RouteDiagnosisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Everything here is under /api/admin/**, which SecurityConfig restricts to ROLE_ADMIN.
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminStatsService adminStatsService;
    private final AdminStatsHistory adminStatsHistory;
    private final AdminFleetService adminFleetService;
    private final FeedHealthService feedHealthService;
    private final RouteDiagnosisService routeDiagnosisService;

    // GET /api/admin/stats - requires a JWT from POST /api/auth/login (see SecurityConfig)
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminStatsService.getStats());
    }

    // GET /api/admin/stats/history - KPI samples from the last two hours, oldest first
    @GetMapping("/stats/history")
    public ResponseEntity<AdminStatsHistory.History> getStatsHistory() {
        return ResponseEntity.ok(adminStatsHistory.history());
    }

    // GET /api/admin/buses?q=&status=MOVING,SLOW&route=&sort=lastSeen,desc&page=0&size=50
    @GetMapping("/buses")
    public ResponseEntity<FleetPageResponse> getBuses(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String route,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(adminFleetService.list(q, status, route, sort, page, size));
    }

    // GET /api/admin/routes/not-responding?limit=8 - routes with buses that went silent today, most first
    @GetMapping("/routes/not-responding")
    public ResponseEntity<RouteDiagnosisService.NotRespondingRoutes> getNotRespondingRoutes(
            @RequestParam(defaultValue = "8") int limit) {
        return ResponseEntity.ok(routeDiagnosisService.notResponding(limit));
    }

    // GET /api/admin/routes/{routeCode}/diagnosis - why each non-reporting bus on the route is silent
    @GetMapping("/routes/{routeCode}/diagnosis")
    public ResponseEntity<RouteDiagnosisService.RouteReport> getRouteDiagnosis(@PathVariable String routeCode) {
        return ResponseEntity.ok(routeDiagnosisService.diagnose(routeCode));
    }

    // GET /api/admin/feed-health - state of the GTFS-realtime poller; never triggers a fetch
    @GetMapping("/feed-health")
    public ResponseEntity<FeedHealthService.FeedHealth> getFeedHealth() {
        return ResponseEntity.ok(feedHealthService.health());
    }
}
