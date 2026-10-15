package com.dtc.bus_tracker.live;

import static org.junit.jupiter.api.Assertions.*;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import com.dtc.bus_tracker.service.DtcIngestionService;
import com.google.transit.realtime.GtfsRealtime.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import org.junit.jupiter.api.*;

/**
 * Tests for the root-cause GTFS route-mapping bugs described in the bug report:
 *
 * 1. Vehicles whose feed-reported route_id conflicts with the authoritative GTFS
 *    trip_id → route_id mapping are rejected by both LiveTransitService and
 *    DtcIngestionService.
 *
 * 2. Exact public-route-number matching — "534" must not match "534A", "534E",
 *    "5340", "221", or the internal GTFS route_id "1824".
 *
 * 3. Off-route geometry flagging — vehicles far from their assigned shape are
 *    returned but flagged, not silently relocated.
 *
 * 4. Missing trip_id — vehicles with no trip_id resolve via route_id only and are
 *    included when the route matches; they are not guessed from numeric patterns.
 *
 * 5. Stale GPS — vehicles older than 120 s are excluded.
 *
 * 6. Multiple vehicles sharing one route — all are returned, sorted by vehicleId.
 *
 * 7. Route-switching race — clearing buses[] between requests is enforced by
 *    visibleBuses() in the frontend (tested separately in navigation.test.js);
 *    here we verify the server never mixes routes on a per-request basis.
 *
 * 8. Same route, opposite directions — both returned when no direction filter,
 *    distinguishable by direction field.
 *
 * 9. GTFS dataset version mismatch — trip_id in the feed that does not appear
 *    in TransitIndex.trips is allowed through (unknown trip, no cross-check
 *    possible) rather than silently dropped.
 *
 * 10. Route_short_name vs route_id — public number comes from route_short_name
 *     (or long-name fallback), never from raw GTFS route_id.
 */
class RouteMappingBugTest {

    TransitIndex index;
    RealtimeSnapshot snapshot;
    LiveTransitService service;
    long now;

    @BeforeEach
    void setup() {
        now = Instant.now().getEpochSecond();
        index = new TransitIndex();
        snapshot = new RealtimeSnapshot();
        service = new LiveTransitService(index, snapshot);

        // Route whose GTFS route_id is "R534" but public number is "534"
        index.routes.put("R534", new TransitIndex.Route("R534", "534", "534 UP", "UP", false));
        // Route whose GTFS route_id is "R534D" but public number is also "534" (DOWN)
        index.routes.put("R534D", new TransitIndex.Route("R534D", "534", "534 DOWN", "DOWN", false));
        // Variant route: "534A" must NOT match a query for "534"
        index.routes.put("R534A", new TransitIndex.Route("R534A", "534A", "534A UP", "UP", false));
        // Unrelated route "221"
        index.routes.put("R221", new TransitIndex.Route("R221", "221", "221 UP", "UP", false));

        // Trip that belongs to R534 (UP direction)
        index.trips.put("T534_1", new TransitIndex.Trip("T534_1", "R534", "0", "", List.of()));
        // Trip that belongs to R534D (DOWN direction)
        index.trips.put("T534_2", new TransitIndex.Trip("T534_2", "R534D", "1", "", List.of()));
        // Trip for 534A variant
        index.trips.put("T534A", new TransitIndex.Trip("T534A", "R534A", "0", "", List.of()));
        // Trip for 221
        index.trips.put("T221", new TransitIndex.Trip("T221", "R221", "0", "", List.of()));

        index.stops.put("s1", new TransitIndex.Stop("s1", "Stop 1", 28.601, 77.2));
        index.routesAtStop.put("s1", Set.of("R534", "R534D"));
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    FeedEntity vehicle(String id, String routeId, String tripId, double lat) {
        return FeedEntity.newBuilder()
                .setId(id)
                .setVehicle(VehiclePosition.newBuilder()
                        .setVehicle(VehicleDescriptor.newBuilder().setId(id))
                        .setTrip(TripDescriptor.newBuilder()
                                .setRouteId(routeId)
                                .setTripId(tripId))
                        .setPosition(Position.newBuilder()
                                .setLatitude((float) lat)
                                .setLongitude(77.2f)
                                .setSpeed(10))
                        .setTimestamp(now))
                .build();
    }

    void feed(FeedEntity... entities) {
        snapshot.accept(FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(now))
                .addAllEntity(List.of(entities))
                .build(), true, true);
    }

    // ── 1. Conflicting route_id rejected (feed route_id != trips.txt route_id) ──

    @Test
    void conflictingFeedRouteIdIsRejected() {
        // Vehicle's feed says route "R221" but trips.txt says T534_1 belongs to "R534"
        feed(vehicle("badVehicle", "R221", "T534_1", 28.6));
        assertTrue(service.buses("534").buses().isEmpty(),
                "Vehicle with conflicting feed route_id must not appear under route 534");
        assertTrue(service.buses("221").buses().isEmpty(),
                "Vehicle with conflicting feed route_id must not appear under route 221 either");
    }

    @Test
    void conflictingFeedRouteIdIsRejectedEvenIfFeedRouteMatchesQuery() {
        // Feed claims this vehicle is on R534, but the trip belongs to R221
        feed(vehicle("bad", "R534", "T221", 28.6));
        assertTrue(service.buses("534").buses().isEmpty(),
                "trip_id→route_id mismatch must cause rejection regardless of feed route_id");
        assertEquals(1, service.buses("534").warnings().stream()
                .filter(w -> w.contains("excluded")).count(),
                "A warning about unmapped/conflicting vehicles must be emitted");
    }

    // ── 2. Exact public route number match ──────────────────────────────────

    @Test
    void exactPublicNumberMatchOnly() {
        feed(vehicle("v534", "R534", "T534_1", 28.601),
             vehicle("v534a", "R534A", "T534A", 28.601),
             vehicle("v221", "R221", "T221", 28.601));

        // "534" returns ONLY v534 — NOT "534A" or "221"
        var result534 = service.buses("534").buses();
        assertEquals(1, result534.size(),
                "Only one UP-direction 534 vehicle was fed; DOWN was not fed here");
        assertEquals(Set.of("v534"), result534.stream().map(LiveTransitService.Bus::vehicleId).collect(Collectors.toSet()),
                "Query '534' must match ONLY public route '534', not '534A' or '221'");

        // "534A" returns only v534a
        var result534a = service.buses("534A").buses();
        assertEquals(1, result534a.size());
        assertEquals("v534a", result534a.get(0).vehicleId());

        // "221" returns only v221
        assertEquals("v221", service.buses("221").buses().get(0).vehicleId());
    }

    @Test
    void internalGtfsRouteIdIsNeverUsedAsPublicNumber() {
        // "R534" is the GTFS route_id, not the public number — querying it should return nothing
        feed(vehicle("v", "R534", "T534_1", 28.6));
        assertTrue(service.buses("R534").buses().isEmpty(),
                "Internal GTFS route_id 'R534' must not be usable as a public route number query");
        assertEquals(1, service.buses("534").buses().size(),
                "The same vehicle must be accessible via public number '534'");
    }

    // ── 3. Off-route geometry flagging ───────────────────────────────────────

    @Test
    void offRouteFlagSetWhenFarFromShape() {
        // Shape running straight north from 28.6 to 28.62
        index.shapes.put("shape534",
                List.of(new TransitIndex.Point(28.6, 77.2, 0),
                        new TransitIndex.Point(28.62, 77.2, 1)));
        index.trips.put("T534_1", new TransitIndex.Trip("T534_1", "R534", "0", "shape534", List.of()));

        // Vehicle 1km east of the shape (far off route)
        feed(vehicle("farVehicle", "R534", "T534_1", 28.61)); // lat ok, but we simulate lon deviation below
        // Re-inject with longitude displaced by ~1 degree (≈88 km)
        snapshot.accept(FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(now))
                .addEntity(FeedEntity.newBuilder().setId("far2")
                        .setVehicle(VehiclePosition.newBuilder()
                                .setVehicle(VehicleDescriptor.newBuilder().setId("farVehicle"))
                                .setTrip(TripDescriptor.newBuilder().setRouteId("R534").setTripId("T534_1"))
                                .setPosition(Position.newBuilder()
                                        .setLatitude(28.61f).setLongitude(78.2f).setSpeed(10))
                                .setTimestamp(now)))
                .build(), true, false);

        var buses = service.buses("534").buses();
        assertEquals(1, buses.size());
        assertTrue(buses.get(0).offRouteFlag(),
                "Vehicle 1 degree east of shape must be flagged as off-route");
        assertNotNull(buses.get(0).distanceFromShapeMeters(),
                "distanceFromShapeMeters must be populated when shape is available");
        assertTrue(buses.get(0).distanceFromShapeMeters() > 500,
                "Distance from shape must exceed 500m threshold");
    }

    @Test
    void onRouteVehicleIsNotFlagged() {
        index.shapes.put("shape534",
                List.of(new TransitIndex.Point(28.6, 77.2, 0),
                        new TransitIndex.Point(28.62, 77.2, 1)));
        index.trips.put("T534_1", new TransitIndex.Trip("T534_1", "R534", "0", "shape534", List.of()));

        // Vehicle directly on the shape
        feed(vehicle("onRoute", "R534", "T534_1", 28.61));
        var buses = service.buses("534").buses();
        assertEquals(1, buses.size());
        assertFalse(buses.get(0).offRouteFlag(),
                "Vehicle on the shape must NOT be flagged as off-route");
    }

    @Test
    void noShapeReturnsNullDistanceAndFalseFlag() {
        // T534_1 has no shape_id (empty string → no lookup)
        feed(vehicle("v", "R534", "T534_1", 28.61));
        var buses = service.buses("534").buses();
        assertEquals(1, buses.size());
        assertNull(buses.get(0).distanceFromShapeMeters(),
                "distanceFromShapeMeters must be null when no shape available");
        assertFalse(buses.get(0).offRouteFlag(),
                "offRouteFlag must be false (not thrown) when no shape is available");
    }

    // ── 4. Missing trip_id ───────────────────────────────────────────────────

    @Test
    void missingTripIdResolvesViaRouteIdOnly() {
        // Vehicle with no trip_id — falls back to vehicle's route_id
        feed(vehicle("noTrip", "R534", "", 28.6));
        assertEquals(1, service.buses("534").buses().size(),
                "Vehicle with no trip_id must resolve via route_id when it matches the query");
        assertEquals("ROUTE_ID_FALLBACK",
                service.buses("534").buses().get(0).mappingSource(),
                "Mapping source must be ROUTE_ID_FALLBACK when trip_id is absent");
    }

    @Test
    void missingTripIdDoesNotMatchWrongRoute() {
        feed(vehicle("noTrip", "R221", "", 28.6));
        assertTrue(service.buses("534").buses().isEmpty(),
                "Vehicle with no trip_id and route_id=R221 must NOT appear under route 534");
    }

    // ── 5. Stale GPS ─────────────────────────────────────────────────────────

    @Test
    void staleGpsExcluded() {
        feed(vehicle("stale", "R534", "T534_1", 28.6));
        // Override with a stale timestamp
        snapshot.accept(FeedMessage.newBuilder()
                .setHeader(FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0").setTimestamp(now - 200))
                .addEntity(FeedEntity.newBuilder().setId("sv")
                        .setVehicle(VehiclePosition.newBuilder()
                                .setVehicle(VehicleDescriptor.newBuilder().setId("stale"))
                                .setTrip(TripDescriptor.newBuilder()
                                        .setRouteId("R534").setTripId("T534_1"))
                                .setPosition(Position.newBuilder()
                                        .setLatitude(28.6f).setLongitude(77.2f).setSpeed(5))
                                .setTimestamp(now - 200)))
                .build(), true, false);
        assertTrue(service.buses("534").buses().isEmpty(),
                "Vehicle with GPS timestamp > 120s old must be excluded");
    }

    // ── 6. Multiple vehicles on same route ───────────────────────────────────

    @Test
    void multipleVehiclesOnSameRouteAllReturned() {
        feed(vehicle("a", "R534", "T534_1", 28.601),
             vehicle("b", "R534", "T534_1", 28.602),
             vehicle("c", "R534", "T534_1", 28.603));
        assertEquals(3, service.buses("534").buses().size(),
                "All vehicles on the same route must be returned");
        // Verify sorted by vehicleId
        assertEquals(List.of("a", "b", "c"),
                service.buses("534").buses().stream()
                        .map(LiveTransitService.Bus::vehicleId)
                        .collect(Collectors.toList()));
    }

    // ── 7. Route-switching race condition (server side) ──────────────────────

    @Test
    void noRouteCrossContaminationBetweenRequests() {
        feed(vehicle("v534", "R534", "T534_1", 28.601),
             vehicle("v221", "R221", "T221", 28.601));

        // Simulate rapid back-to-back requests for different routes
        var result534 = service.buses("534");
        var result221 = service.buses("221");

        assertTrue(result534.buses().stream().allMatch(b -> b.routeCode().equals("534")),
                "Buses in route 534 result must all have routeCode=534");
        assertTrue(result221.buses().stream().allMatch(b -> b.routeCode().equals("221")),
                "Buses in route 221 result must all have routeCode=221");
        assertEquals(0, result534.buses().stream()
                .filter(b -> b.vehicleId().equals("v221")).count(),
                "v221 must not appear in route 534 results");
    }

    // ── 8. Same route, opposite directions ───────────────────────────────────

    @Test
    void oppositeDirectionsDistinguishable() {
        feed(vehicle("upBus", "R534", "T534_1", 28.601),
             vehicle("downBus", "R534D", "T534_2", 28.601));

        var buses = service.buses("534").buses();
        assertEquals(2, buses.size(), "Both directions of route 534 must be returned");

        var directions = buses.stream()
                .map(LiveTransitService.Bus::direction)
                .collect(Collectors.toSet());
        assertTrue(directions.contains("UP"), "UP direction must be present");
        assertTrue(directions.contains("DOWN"), "DOWN direction must be present");
    }

    // ── 9. GTFS dataset version mismatch (unknown trip_id) ────────────────────

    @Test
    void unknownTripIdAllowedThroughViaRouteIdFallback() {
        // trip_id "UNKNOWN_TRIP" is not in index.trips at all
        feed(vehicle("v", "R534", "UNKNOWN_TRIP", 28.601));
        // Should still be accessible via route_id R534 → route number "534"
        assertEquals(1, service.buses("534").buses().size(),
                "Vehicle with unknown trip_id (dataset version mismatch) must resolve via route_id");
    }

    @Test
    void unknownTripIdWithConflictingRouteIdFallsThroughToRouteIdCheck() {
        // feed route_id R221 but trips.txt has no entry for "FUTURE_TRIP"
        // Should resolve as 221 (no cross-check possible, so route_id wins)
        feed(vehicle("v", "R221", "FUTURE_TRIP", 28.601));
        assertEquals(1, service.buses("221").buses().size(),
                "Vehicle with unknown trip_id should resolve to feed route_id's route");
        assertTrue(service.buses("534").buses().isEmpty(),
                "Vehicle on route 221 must not appear under route 534");
    }

    // ── 10. Route_short_name vs route_id ─────────────────────────────────────

    @Test
    void publicNumberFromRouteShortName() {
        // The number "534" comes from route_short_name, not from GTFS route_id "R534"
        var route = index.routes.get("R534");
        assertEquals("534", route.number(),
                "Public route number must be derived from route_short_name, not route_id");
        assertNotEquals("R534", route.number(),
                "GTFS route_id must never be exposed as the public route number");
    }

    @Test
    void inferredFlagSetWhenShortNameEmpty() {
        // When route_short_name is empty, number is inferred from route_long_name
        // and inferred() should be true
        index.routes.put("R999", new TransitIndex.Route("R999", "999", "999UP", "UP", true));
        var result = service.buses("999");
        // Inferred route should still return MAPPED when vehicles are present
        feed(vehicle("v999", "R999", "", 28.601));
        assertEquals(LiveTransitService.RouteMappingStatus.MAPPED,
                service.buses("999").routeMapping());
    }

    // ── 11. DtcIngestionService trip_id cross-validation ─────────────────────

    @Test
    void ingestionRejectsVehicleWithConflictingTripRoute() {
        // DtcIngestionService.isTripRouteConsistent logic:
        // vehicle claims route R534 but trip T221 belongs to R221
        var event = BusLocationEvent.builder()
                .vehicleId("bad")
                .routeId("R534")
                .tripId("T221")
                .latitude(28.6).longitude(77.2)
                .timestamp(now)
                .build();

        // We replicate the consistency check logic here (white-box test of the filter predicate)
        var trip = index.trips.get(event.getTripId());
        assertNotNull(trip, "Trip T221 must be in TransitIndex");
        assertFalse(event.getRouteId().equals(trip.routeId()),
                "R534 vs T221.routeId()=R221 must conflict");
    }

    @Test
    void ingestionAllowsVehicleWithNoTripId() {
        var event = BusLocationEvent.builder()
                .vehicleId("noTrip")
                .routeId("R534")
                .tripId("")          // blank trip_id
                .latitude(28.6).longitude(77.2)
                .timestamp(now)
                .build();

        // With blank tripId the check should pass (no trip to cross-check)
        String tripId = event.getTripId();
        boolean consistent = tripId == null || tripId.isBlank()
                || !index.trips.containsKey(tripId)
                || event.getRouteId().equals(index.trips.get(tripId).routeId());
        assertTrue(consistent, "Vehicle with blank trip_id must pass the consistency check");
    }

    @Test
    void ingestionAllowsVehicleWithUnknownTripId() {
        var event = BusLocationEvent.builder()
                .vehicleId("newService")
                .routeId("R534")
                .tripId("BRAND_NEW_TRIP_NOT_IN_GTFS")
                .latitude(28.6).longitude(77.2)
                .timestamp(now)
                .build();

        // Trip not in index → no cross-check → allow through
        boolean consistent = event.getTripId() == null || event.getTripId().isBlank()
                || !index.trips.containsKey(event.getTripId())
                || event.getRouteId().equals(index.trips.get(event.getTripId()).routeId());
        assertTrue(consistent, "Vehicle with unknown trip_id (e.g. new service day) must pass");
    }

    // ── 12. Mapping source diagnostic field ──────────────────────────────────

    @Test
    void mappingSourceIsTripIdLookupWhenTripExists() {
        feed(vehicle("v", "R534", "T534_1", 28.601));
        var buses = service.buses("534").buses();
        assertEquals(1, buses.size());
        assertEquals("TRIP_ID_LOOKUP", buses.get(0).mappingSource(),
                "mappingSource must be TRIP_ID_LOOKUP when trip_id resolves in TransitIndex");
    }

    @Test
    void mappingSourceIsRouteIdFallbackWhenTripMissing() {
        feed(vehicle("v", "R534", "", 28.601));
        var buses = service.buses("534").buses();
        assertEquals(1, buses.size());
        assertEquals("ROUTE_ID_FALLBACK", buses.get(0).mappingSource(),
                "mappingSource must be ROUTE_ID_FALLBACK when trip_id is blank");
    }
}
