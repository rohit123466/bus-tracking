package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis keeps the latest position per bus across restarts; reads are served from a copy held in
 * this process. Reading Redis directly cost one round trip per bus on every admin, nearby and
 * stop request (thousands of them), which on a small hosted instance took longer than the
 * client's timeout. This process is the only writer, so the copy stays exact: it is loaded from
 * Redis once, then updated on every save.
 */
@Service
@Profile("!demo")
@RequiredArgsConstructor
public class RedisBusLocationStore implements BusLocationStore {

    private static final String PREFIX = "bus:";
    private static final int LOAD_CHUNK = 500;

    private final RedisTemplate<String, BusLocationEvent> redisTemplate;
    private final ConcurrentHashMap<String, BusLocationEvent> latest = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    @Override
    public void save(BusLocationEvent event) {
        redisTemplate.opsForValue().set(PREFIX + event.getVehicleId(), event);
        latest.put(event.getVehicleId(), event);
    }

    @Override
    public Collection<BusLocationEvent> findAll() {
        load();
        return List.copyOf(latest.values());
    }

    @Override
    public Optional<BusLocationEvent> findByVehicleId(String vehicleId) {
        load();
        return Optional.ofNullable(latest.get(vehicleId));
    }

    /** Positions saved before this process started. A failure propagates and is retried on the next call. */
    private void load() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            Set<String> keys = redisTemplate.keys(PREFIX + "*");
            if (keys != null && !keys.isEmpty()) {
                List<String> ordered = new ArrayList<>(keys);
                for (int from = 0; from < ordered.size(); from += LOAD_CHUNK) {
                    List<BusLocationEvent> values = redisTemplate.opsForValue()
                            .multiGet(ordered.subList(from, Math.min(from + LOAD_CHUNK, ordered.size())));
                    if (values == null) continue;
                    for (BusLocationEvent e : values) {
                        // A save made while loading is newer than what Redis returned.
                        if (e != null && e.getVehicleId() != null) latest.putIfAbsent(e.getVehicleId(), e);
                    }
                }
            }
            loaded = true;
        }
    }
}
