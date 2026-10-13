package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.JourneyLeg;
import com.dtc.bus_tracker.dto.JourneyOption;
import com.dtc.bus_tracker.dto.JourneyPlanResponse;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Heuristic journey planning over the static GTFS route graph: nearest
 * boarding/alighting stops, a direct route if one connects them in the right
 * direction, otherwise a single transfer via a stop shared by two routes.
 * This is graph-search over stop sequences, not a full time-expanded transit
 * router (no schedule-adherence or multi-transfer search) - a reasonable
 * scope for the stop/route data this project has.
 *
 * <p>Three journey preferences are supported via {@link JourneyPreference}:
 * FASTEST (default), FEWEST_TRANSFERS, and MINIMUM_WALKING.
 */
@Service
@RequiredArgsConstructor
public class JourneyPlannerService {

    /** Sorting preference for journey options. */
    public enum JourneyPreference { FASTEST, FEWEST_TRANSFERS, MINIMUM_WALKING }

    private static final double WALK_SPEED_KMH = 5.0;
    private static final double BUS_SPEED_KMH = 20.0;
    private static final double CANDIDATE_RADIUS_METERS = 3000;
    private static final int MAX_CANDIDATE_STOPS = 5;
    private static final int MAX_OPTIONS = 5;

    private final StopRepository stopRepository;
    private final RouteStopSequenceService routeStopSequenceService;

    /** Backwards-compatible overload — defaults to FASTEST preference. */
    public JourneyPlanResponse plan(double srcLat, double srcLng, double dstLat, double dstLng) {
        return plan(srcLat, srcLng, dstLat, dstLng, JourneyPreference.FASTEST);
    }

    public JourneyPlanResponse plan(double srcLat, double srcLng, double dstLat, double dstLng,
                                    JourneyPreference preference) {
        List<Stop> boardingCandidates = nearestStops(srcLat, srcLng);
        List<Stop> alightingCandidates = nearestStops(dstLat, dstLng);

        List<JourneyOption> options = findDirectOptions(srcLat, srcLng, dstLat, dstLng, boardingCandidates, alightingCandidates);
        if (options.isEmpty()) {
            options = findTransferOptions(srcLat, srcLng, dstLat, dstLng, boardingCandidates, alightingCandidates);
        }

        // Sort by the requested preference, then by totalMinutes as a tiebreaker.
        Comparator<JourneyOption> comparator = switch (preference) {
            case FEWEST_TRANSFERS -> Comparator.comparingInt((JourneyOption o) -> o.getLegs().size())
                    .thenComparingInt(JourneyOption::getTotalMinutes);
            case MINIMUM_WALKING -> Comparator.comparingDouble(
                    (JourneyOption o) -> o.getWalkToBoardMeters() + o.getWalkFromAlightMeters())
                    .thenComparingInt(JourneyOption::getTotalMinutes);
            default -> Comparator.comparingInt(JourneyOption::getTotalMinutes);
        };
        options.sort(comparator);
        return JourneyPlanResponse.builder()
                .options(options.stream().limit(MAX_OPTIONS).toList())
                .build();
    }

    private List<JourneyOption> findDirectOptions(double srcLat, double srcLng, double dstLat, double dstLng,
                                                   List<Stop> boardingCandidates, List<Stop> alightingCandidates) {
        List<JourneyOption> options = new ArrayList<>();
        for (Stop board : boardingCandidates) {
            for (Route route : board.getRoutes()) {
                List<Stop> ordered = routeStopSequenceService.orderedStops(route);
                int boardIdx = indexOfStop(ordered, board.getId());
                if (boardIdx < 0) continue;

                for (Stop alight : alightingCandidates) {
                    if (alight.getId().equals(board.getId())) continue;
                    int alightIdx = indexOfStop(ordered, alight.getId());
                    if (alightIdx <= boardIdx) continue; // not served, or wrong direction

                    double inVehicleDistance = distanceAlong(ordered, boardIdx, alightIdx);
                    double walkToBoard = GeoUtils.haversine(srcLat, srcLng, board.getLatitude(), board.getLongitude());
                    double walkFromAlight = GeoUtils.haversine(dstLat, dstLng, alight.getLatitude(), alight.getLongitude());

                    JourneyLeg leg = JourneyLeg.builder()
                            .routeId(route.getId())
                            .routeCode(route.getRouteCode())
                            .routeName(route.getName())
                            .boardStopName(board.getName())
                            .alightStopName(alight.getName())
                            .inVehicleDistanceMeters(inVehicleDistance)
                            .inVehicleMinutes(minutesAt(inVehicleDistance, BUS_SPEED_KMH))
                            .build();

                    int totalMinutes = minutesAt(walkToBoard, WALK_SPEED_KMH)
                            + leg.getInVehicleMinutes()
                            + minutesAt(walkFromAlight, WALK_SPEED_KMH);

                    options.add(JourneyOption.builder()
                            .direct(true)
                            .legs(List.of(leg))
                            .walkToBoardMeters(walkToBoard)
                            .walkFromAlightMeters(walkFromAlight)
                            .totalMinutes(totalMinutes)
                            .build());
                }
            }
        }
        return options;
    }

    private List<JourneyOption> findTransferOptions(double srcLat, double srcLng, double dstLat, double dstLng,
                                                      List<Stop> boardingCandidates, List<Stop> alightingCandidates) {
        List<JourneyOption> options = new ArrayList<>();

        for (Stop board : boardingCandidates) {
            for (Route routeA : board.getRoutes()) {
                List<Stop> orderedA = routeStopSequenceService.orderedStops(routeA);
                int boardIdx = indexOfStop(orderedA, board.getId());
                if (boardIdx < 0) continue;

                for (int t = boardIdx + 1; t < orderedA.size(); t++) {
                    Stop transfer = orderedA.get(t);

                    for (Route routeB : transfer.getRoutes()) {
                        if (routeB.getId().equals(routeA.getId())) continue;
                        List<Stop> orderedB = routeStopSequenceService.orderedStops(routeB);
                        int transferIdx = indexOfStop(orderedB, transfer.getId());
                        if (transferIdx < 0) continue;

                        for (Stop alight : alightingCandidates) {
                            int alightIdx = indexOfStop(orderedB, alight.getId());
                            if (alightIdx <= transferIdx) continue;

                            double leg1Distance = distanceAlong(orderedA, boardIdx, t);
                            double leg2Distance = distanceAlong(orderedB, transferIdx, alightIdx);
                            double walkToBoard = GeoUtils.haversine(srcLat, srcLng, board.getLatitude(), board.getLongitude());
                            double walkFromAlight = GeoUtils.haversine(dstLat, dstLng, alight.getLatitude(), alight.getLongitude());

                            JourneyLeg leg1 = JourneyLeg.builder()
                                    .routeId(routeA.getId()).routeCode(routeA.getRouteCode()).routeName(routeA.getName())
                                    .boardStopName(board.getName()).alightStopName(transfer.getName())
                                    .inVehicleDistanceMeters(leg1Distance)
                                    .inVehicleMinutes(minutesAt(leg1Distance, BUS_SPEED_KMH))
                                    .build();
                            JourneyLeg leg2 = JourneyLeg.builder()
                                    .routeId(routeB.getId()).routeCode(routeB.getRouteCode()).routeName(routeB.getName())
                                    .boardStopName(transfer.getName()).alightStopName(alight.getName())
                                    .inVehicleDistanceMeters(leg2Distance)
                                    .inVehicleMinutes(minutesAt(leg2Distance, BUS_SPEED_KMH))
                                    .build();

                            int totalMinutes = minutesAt(walkToBoard, WALK_SPEED_KMH)
                                    + leg1.getInVehicleMinutes()
                                    + leg2.getInVehicleMinutes()
                                    + minutesAt(walkFromAlight, WALK_SPEED_KMH);

                            options.add(JourneyOption.builder()
                                    .direct(false)
                                    .legs(List.of(leg1, leg2))
                                    .walkToBoardMeters(walkToBoard)
                                    .walkFromAlightMeters(walkFromAlight)
                                    .totalMinutes(totalMinutes)
                                    .build());

                            if (options.size() >= 20) return options; // enough candidates to rank from
                        }
                    }
                }
            }
        }
        return options;
    }

    private List<Stop> nearestStops(double lat, double lng) {
        return stopRepository.findAll().stream()
                .filter(stop -> GeoUtils.haversine(lat, lng, stop.getLatitude(), stop.getLongitude()) <= CANDIDATE_RADIUS_METERS)
                .sorted(Comparator.comparingDouble(stop -> GeoUtils.haversine(lat, lng, stop.getLatitude(), stop.getLongitude())))
                .limit(MAX_CANDIDATE_STOPS)
                .toList();
    }

    private int indexOfStop(List<Stop> ordered, Long stopId) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(stopId)) return i;
        }
        return -1;
    }

    private double distanceAlong(List<Stop> ordered, int fromIdx, int toIdx) {
        double total = 0;
        for (int i = fromIdx; i < toIdx; i++) {
            Stop a = ordered.get(i);
            Stop b = ordered.get(i + 1);
            total += GeoUtils.haversine(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
        }
        return total;
    }

    private int minutesAt(double distanceMeters, double speedKmh) {
        double metersPerMinute = speedKmh * 1000.0 / 60.0;
        return (int) Math.ceil(distanceMeters / metersPerMinute);
    }
}
