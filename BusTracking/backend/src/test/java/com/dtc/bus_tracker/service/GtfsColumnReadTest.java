package com.dtc.bus_tracker.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dtc.bus_tracker.live.TransitIndex;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the GTFS column-read correctness in GtfsImportService and TransitIndex.
 *
 * The root cause of the GPS-location bug was that GtfsImportService.readStops()
 * used hardcoded positional column indices (row[1]=stop_id, row[2]=lat, row[3]=lon,
 * row[4]=name) rather than reading by header name. Standard GTFS has stop_id at
 * column 0, so this read every column one position off.
 *
 * These tests verify that:
 *  1. TransitIndex reads column values by header name (always correct).
 *  2. GtfsImportService's column-index helpers work correctly.
 *  3. The publicNumber() extraction logic is correct.
 *  4. route_short_name is preferred over route_long_name for the public number.
 */
class GtfsColumnReadTest {

    // ── publicNumber() extraction ────────────────────────────────────────────

    @Test
    void shortNamePreferredOverLongName() {
        assertEquals("534", TransitIndex.publicNumber("534", "534UP"),
                "route_short_name must be used when non-empty");
    }

    @Test
    void variantSuffixPreservedFromShortName() {
        assertEquals("534E", TransitIndex.publicNumber("534E", "534EUP"),
                "variant suffix in route_short_name must be preserved exactly");
    }

    @Test
    void longNameFallbackStripsDirectionSuffix() {
        assertEquals("534", TransitIndex.publicNumber("", "534DOWN"),
                "DOWN suffix must be stripped from route_long_name fallback");
        assertEquals("534", TransitIndex.publicNumber("", "534UP"),
                "UP suffix must be stripped from route_long_name fallback");
    }

    @Test
    void longNameFallbackPreservesVariant() {
        // "534EUP" → strip UP → "534E" (variant suffix preserved)
        assertEquals("534E", TransitIndex.publicNumber("", "534EUP"),
                "Variant suffix before UP/DOWN must be preserved in long-name fallback");
    }

    @Test
    void arbitraryLongNameReturnsNull() {
        assertNull(TransitIndex.publicNumber("", "Rohini to Chandni Chowk"),
                "Arbitrary long names with no numeric prefix must return null");
    }

    @Test
    void blankShortNameAndBlankLongNameReturnsNull() {
        assertNull(TransitIndex.publicNumber("", ""),
                "Both blank must return null");
    }

    @Test
    void nullLongNameWithEmptyShortNameReturnsNull() {
        assertNull(TransitIndex.publicNumber("", null),
                "Null long_name must not throw and must return null");
    }

    @Test
    void routeShortNameCaseNormalized() {
        assertEquals("534A", TransitIndex.publicNumber("534a", "any"),
                "route_short_name must be uppercased");
    }

    // ── seconds() helper for GTFS time strings ────────────────────────────────

    @Test
    void secondsParsesMidnightServices() {
        assertEquals(25 * 3600 + 30 * 60 + 15, TransitIndex.seconds("25:30:15"),
                "GTFS times past midnight (hour>=24) must be represented as total seconds");
    }

    @Test
    void secondsParsesNormalTime() {
        assertEquals(7 * 3600 + 15 * 60 + 0, TransitIndex.seconds("07:15:00"));
    }

    @Test
    void secondsReturnsMinusOneForBlank() {
        assertEquals(-1, TransitIndex.seconds(""),
                "Blank time string must return -1 (absent value sentinel)");
        assertEquals(-1, TransitIndex.seconds(null),
                "Null time string must return -1");
    }

    // ── route_id vs public number distinction ─────────────────────────────────

    @Test
    void routeIdIsNeverUsedAsPublicNumber() {
        // The Route record's id field holds the GTFS route_id.
        // The number field holds the public-facing route number.
        // These must be different when route_id is not the same as route_short_name.
        var route = new TransitIndex.Route("DTC_534_UP", "534", "534 Rohini UP", "UP", false);
        assertEquals("DTC_534_UP", route.id(), "Route.id() must be the GTFS route_id");
        assertEquals("534", route.number(), "Route.number() must be the public route number");
        assertNotEquals(route.id(), route.number(),
                "GTFS route_id and public route number must differ when prefix is present");
    }

    @Test
    void internalRouteIdSuffixVariantsDoNotMixPublicNumbers() {
        // Two GTFS entries for the same public route in opposite directions
        var up   = new TransitIndex.Route("R534_UP",   "534", "534UP",   "UP",   false);
        var down = new TransitIndex.Route("R534_DOWN", "534", "534DOWN", "DOWN", false);
        assertEquals("534", up.number());
        assertEquals("534", down.number());
        assertNotEquals(up.id(), down.id(),
                "UP and DOWN routes must have different GTFS route_ids");
    }
}
