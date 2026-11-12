package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.repository.BusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The bus registry with each bus's route, for the admin console. The fleet list, the
 * "not responding" scan and the KPI cards each read every bus (thousands of rows, joined with its
 * route) on every request, and the fleet list is fetched as several pages in a row, so one console
 * load read the whole table many times over.
 *
 * <p>The registry only changes when the feed brings a bus or route assignment it has not seen
 * ({@link BusLocationIngestService} then calls {@link #invalidate()}), so the list is reused until
 * that happens, and for at most {@link #MAX_AGE_MS} in case a row is changed some other way.
 * The rows are detached entities with their route loaded; callers only read them.
 */
@Component
@RequiredArgsConstructor
public class BusRegistryCache {
    static final long MAX_AGE_MS = 10_000;

    private record Loaded(List<Bus> buses, long version, long at) {}

    private final BusRepository busRepository;
    private volatile Loaded loaded;
    private volatile long version;

    /** Every registered bus with its route. */
    public List<Bus> all() {
        Loaded current = loaded;
        if (current != null && current.version() == version
                && System.currentTimeMillis() - current.at() < MAX_AGE_MS) return current.buses();
        synchronized (this) {
            current = loaded;
            if (current != null && current.version() == version
                    && System.currentTimeMillis() - current.at() < MAX_AGE_MS) return current.buses();
            long v = version; // read before loading: a change during the load forces the next reload
            List<Bus> buses = List.copyOf(busRepository.findAllWithRoute());
            loaded = new Loaded(buses, v, System.currentTimeMillis());
            return buses;
        }
    }

    /** Number of registered buses (same rows as {@link #all()}). */
    public long count() {
        return all().size();
    }

    /** A bus row was added or changed: the next read loads the registry again. */
    public void invalidate() {
        version++;
    }

    private volatile long liveVersion;

    /** Live positions were stored (the only writer is {@link BusLocationIngestService}). */
    public void liveChanged() {
        liveVersion++;
    }

    /** Changes whenever the registry or the live positions change, for answers derived from both. */
    public long fleetVersion() {
        return version * 1_000_003L + liveVersion;
    }
}
