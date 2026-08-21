package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Trip;
import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.repository.StopTimeRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import com.opencsv.CSVReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports the bundled DTC/DIMTS GTFS feed. The full feed covers all of Delhi
 * (10k+ stops, 3.7M stop_times rows) which is slow to import and more data
 * than a local demo needs, so the import is restricted to stops within
 * {@code gtfs.import.radius-degrees} of {@code gtfs.import.center-lat/lng}
 * (default: a box covering North Delhi and North West Delhi - Civil Lines,
 * Rohini, Pitampura, Shalimar Bagh, Narela, Bawana, Mangolpuri, etc).
 * Routes/trips/stop_times are then pruned to only what actually serves those
 * stops. Set radius-degrees to 0 or less to import the entire feed.
 */
@Service
public class GtfsImportService {

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;

    private static final String GTFS_ZIP_PATH = "static/GTFS.zip";

    @Value("${gtfs.import.center-lat:28.77}")
    private double centerLat;
    @Value("${gtfs.import.center-lng:77.09}")
    private double centerLng;
    @Value("${gtfs.import.radius-degrees:0.16}")
    private double radiusDegrees;

    public GtfsImportService(
            RouteRepository routeRepository,
            StopRepository stopRepository,
            TripRepository tripRepository,
            StopTimeRepository stopTimeRepository) {
        this.routeRepository = routeRepository;
        this.stopRepository = stopRepository;
        this.tripRepository = tripRepository;
        this.stopTimeRepository = stopTimeRepository;
    }

    private record StopRow(String stopId, String name, double lat, double lon, boolean wheelchairBoarding) {}
    private record TripRow(String routeCode, String serviceId, String tripId, String shapeId, boolean wheelchairAccessible) {}

    /**
     * A real GTFS feed zip is many MB; an unresolved Git LFS pointer file is a
     * ~130-byte text stub starting with this line. If the build/deploy
     * pipeline didn't fetch LFS content, we'd otherwise silently import zero
     * rows instead of failing loudly.
     */
    private static final String LFS_POINTER_MAGIC = "version https://git-lfs.github.com/spec/v1";

    public void importAll() throws Exception {
        if (routeRepository.count() > 0) {
            System.out.println("GTFS already seeded, skipping import.");
            return;
        }

        assertGtfsZipIsResolved();

        boolean filtered = radiusDegrees > 0;
        System.out.println(filtered
                ? "Importing GTFS subset within " + radiusDegrees + " deg of (" + centerLat + ", " + centerLng + ")"
                : "Importing full GTFS feed (no geographic filter)");

        // 1. stops.txt -> which stop_ids are in scope
        List<StopRow> stopRows = readStops();
        List<StopRow> keptStops = filtered ? filterStopsByBoundingBox(stopRows) : stopRows;
        Set<String> keptStopIds = new HashSet<>();
        for (StopRow s : keptStops) keptStopIds.add(s.stopId());

        // 2. stop_times.txt (pass 1, no inserts) -> which trip_ids touch a kept stop
        Set<String> keptTripIds = filtered ? collectTripIdsForStops(keptStopIds) : null;

        // 3. trips.txt -> which trips (and therefore route_codes) are in scope
        List<TripRow> tripRows = readTrips();
        List<TripRow> keptTrips = filtered
                ? tripRows.stream().filter(t -> keptTripIds != null && keptTripIds.contains(t.tripId())).toList()
                : tripRows;
        Set<String> keptRouteCodes = new HashSet<>();
        for (TripRow t : keptTrips) keptRouteCodes.add(t.routeCode());

        // 4. routes.txt -> save only routes actually serving a kept stop
        Map<String, Route> routeByCode = importRoutes(keptRouteCodes, filtered);

        // 5. save kept stops
        Map<String, Stop> stopByStopId = importStops(keptStops);

        // 6. save kept trips
        Map<String, Trip> tripByTripId = importTrips(keptTrips, routeByCode);

        // 7. stop_times.txt (pass 2) -> actual insert, in-memory lookups only
        importStopTimes(tripByTripId, stopByStopId);
    }

    private void assertGtfsZipIsResolved() throws Exception {
        ClassPathResource resource = new ClassPathResource(GTFS_ZIP_PATH);
        byte[] head = new byte[64];
        int read;
        try (var in = resource.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        }
        String prefix = new String(head, 0, read, java.nio.charset.StandardCharsets.US_ASCII);
        if (prefix.startsWith(LFS_POINTER_MAGIC)) {
            throw new IllegalStateException(
                    "GTFS.zip is an unresolved Git LFS pointer file, not the real archive. "
                            + "Enable Git LFS fetching in the build/deploy pipeline (`git lfs pull`) "
                            + "before the app starts.");
        }
    }

    private boolean inBoundingBox(double lat, double lon) {
        return lat >= centerLat - radiusDegrees && lat <= centerLat + radiusDegrees
                && lon >= centerLng - radiusDegrees && lon <= centerLng + radiusDegrees;
    }

    private List<StopRow> filterStopsByBoundingBox(List<StopRow> rows) {
        List<StopRow> kept = new ArrayList<>();
        for (StopRow row : rows) {
            if (inBoundingBox(row.lat(), row.lon())) {
                kept.add(row);
            }
        }
        System.out.println("Bounding box kept " + kept.size() + " of " + rows.size() + " stops.");
        return kept;
    }

    private List<StopRow> readStops() throws Exception {
        List<StopRow> rows = new ArrayList<>();
        withZipEntry("stops.txt", zis -> {
            try (CSVReader reader = new CSVReader(new InputStreamReader(zis))) {
                String[] header = reader.readNext();
                int wheelchairIdx = columnIndex(header, "wheelchair_boarding");
                String[] row;
                while ((row = reader.readNext()) != null) {
                    rows.add(new StopRow(row[1], row[4], Double.parseDouble(row[2]), Double.parseDouble(row[3]),
                            isFlagSet(row, wheelchairIdx)));
                }
            }
        });
        return rows;
    }

    private List<TripRow> readTrips() throws Exception {
        List<TripRow> rows = new ArrayList<>();
        withZipEntry("trips.txt", zis -> {
            try (CSVReader reader = new CSVReader(new InputStreamReader(zis))) {
                String[] header = reader.readNext();
                int wheelchairIdx = columnIndex(header, "wheelchair_accessible");
                String[] row;
                while ((row = reader.readNext()) != null) {
                    rows.add(new TripRow(row[0], row[1], row[2], row.length > 3 ? row[3] : null,
                            isFlagSet(row, wheelchairIdx)));
                }
            }
        });
        return rows;
    }

    /**
     * wheelchair_accessible/wheelchair_boarding are optional GTFS columns (not
     * present at a fixed position, and absent entirely from the bundled feed),
     * so look them up by header name and default to false/unknown when missing
     * or set to "1" (accessible) per the GTFS spec.
     */
    private int columnIndex(String[] header, String columnName) {
        if (header == null) return -1;
        for (int i = 0; i < header.length; i++) {
            if (columnName.equalsIgnoreCase(header[i].trim())) return i;
        }
        return -1;
    }

    private boolean isFlagSet(String[] row, int columnIndex) {
        return columnIndex >= 0 && columnIndex < row.length && "1".equals(row[columnIndex].trim());
    }

    private Set<String> collectTripIdsForStops(Set<String> keptStopIds) throws Exception {
        Set<String> tripIds = new HashSet<>();
        withZipEntry("stop_times.txt", zis -> {
            try (CSVReader reader = new CSVReader(new InputStreamReader(zis))) {
                reader.readNext();
                String[] row;
                while ((row = reader.readNext()) != null) {
                    if (keptStopIds.contains(row[3])) {
                        tripIds.add(row[0]);
                    }
                }
            }
        });
        System.out.println("Bounding box touches " + tripIds.size() + " trips.");
        return tripIds;
    }

    private Map<String, Route> importRoutes(Set<String> keptRouteCodes, boolean filtered) throws Exception {
        Map<String, Route> byCode = new HashMap<>();
        withZipEntry("routes.txt", zis -> {
            try (CSVReader reader = new CSVReader(new InputStreamReader(zis))) {
                reader.readNext();
                String[] row;
                int count = 0;
                while ((row = reader.readNext()) != null) {
                    String routeCode = row[1];
                    if (filtered && !keptRouteCodes.contains(routeCode)) continue;

                    Route route = Route.builder()
                            .routeCode(routeCode)
                            .name(row[2].isBlank() ? row[3] : row[2])
                            .build();
                    routeRepository.save(route);
                    byCode.put(routeCode, route);
                    count++;
                }
                System.out.println("Imported " + count + " routes.");
            }
        });
        return byCode;
    }

    private Map<String, Stop> importStops(List<StopRow> keptStops) {
        Map<String, Stop> byStopId = new HashMap<>();
        for (StopRow row : keptStops) {
            Stop stop = Stop.builder()
                    .stopId(row.stopId())
                    .name(row.name())
                    .latitude(row.lat())
                    .longitude(row.lon())
                    .wheelchairBoarding(row.wheelchairBoarding())
                    .build();
            stopRepository.save(stop);
            byStopId.put(row.stopId(), stop);
        }
        System.out.println("Imported " + byStopId.size() + " stops.");
        return byStopId;
    }

    private Map<String, Trip> importTrips(List<TripRow> keptTrips, Map<String, Route> routeByCode) {
        Map<String, Trip> byTripId = new HashMap<>();
        int skipped = 0;
        for (TripRow row : keptTrips) {
            Route route = routeByCode.get(row.routeCode());
            if (route == null) {
                skipped++;
                continue;
            }
            Trip trip = Trip.builder()
                    .tripId(row.tripId())
                    .serviceId(row.serviceId())
                    .shapeId(row.shapeId())
                    .route(route)
                    .wheelchairAccessible(row.wheelchairAccessible())
                    .build();
            tripRepository.save(trip);
            byTripId.put(row.tripId(), trip);
        }
        System.out.println("Imported " + byTripId.size() + " trips. Skipped " + skipped + " (route not found).");
        return byTripId;
    }

    private void importStopTimes(Map<String, Trip> tripByTripId, Map<String, Stop> stopByStopId) throws Exception {
        int batchSize = 1000;
        List<StopTime> batch = new ArrayList<>();
        int[] counters = {0, 0}; // count, skipped
        // A route "serves" a stop if any of its trips has a stop_time there.
        // Nothing else populates this, so without it every route-stop lookup
        // (route detail, stop search, journey planning) sees empty routes/stops.
        Map<Stop, Set<Route>> routesByStop = new HashMap<>();

        withZipEntry("stop_times.txt", zis -> {
            try (CSVReader reader = new CSVReader(new InputStreamReader(zis))) {
                reader.readNext();
                String[] row;
                while ((row = reader.readNext()) != null) {
                    Trip trip = tripByTripId.get(row[0]);
                    Stop stop = stopByStopId.get(row[3]);
                    if (trip == null || stop == null) {
                        counters[1]++;
                        continue;
                    }

                    StopTime st = StopTime.builder()
                            .trip(trip)
                            .stop(stop)
                            .arrivalTime(parseGtfsTime(row[1]))
                            .departureTime(parseGtfsTime(row[2]))
                            .stopSequence(Integer.parseInt(row[4]))
                            .build();

                    batch.add(st);
                    counters[0]++;
                    routesByStop.computeIfAbsent(stop, k -> new HashSet<>()).add(trip.getRoute());

                    if (batch.size() >= batchSize) {
                        stopTimeRepository.saveAll(batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    stopTimeRepository.saveAll(batch);
                }
                System.out.println("Total stop_times imported: " + counters[0] + ". Skipped: " + counters[1]);
            }
        });

        linkStopsToRoutes(routesByStop);
    }

    private void linkStopsToRoutes(Map<Stop, Set<Route>> routesByStop) {
        for (Map.Entry<Stop, Set<Route>> entry : routesByStop.entrySet()) {
            entry.getKey().setRoutes(new ArrayList<>(entry.getValue()));
        }
        stopRepository.saveAll(routesByStop.keySet());
        System.out.println("Linked " + routesByStop.size() + " stops to their serving routes.");
    }

    @FunctionalInterface
    private interface ZipEntryConsumer {
        void accept(ZipInputStream zis) throws Exception;
    }

    private void withZipEntry(String entryName, ZipEntryConsumer consumer) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ClassPathResource(GTFS_ZIP_PATH).getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    consumer.accept(zis);
                    return;
                }
            }
        }
    }

    /**
     * GTFS allows hours >= 24 (e.g. "24:02:04") to represent service continuing
     * past midnight into the next day. LocalTime can't hold that, so the hour
     * wraps mod 24 and the day-overflow is dropped - fine for display purposes.
     */
    private LocalTime parseGtfsTime(String raw) {
        String[] parts = raw.split(":");
        int hour = Integer.parseInt(parts[0]) % 24;
        int minute = Integer.parseInt(parts[1]);
        int second = Integer.parseInt(parts[2]);
        return LocalTime.of(hour, minute, second);
    }
}
