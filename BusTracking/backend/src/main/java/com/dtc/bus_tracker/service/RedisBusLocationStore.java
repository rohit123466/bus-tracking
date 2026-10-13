package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Profile("!demo")
@RequiredArgsConstructor
public class RedisBusLocationStore implements BusLocationStore {

    private final RedisTemplate<String, BusLocationEvent> redisTemplate;

    @Override
    public void save(BusLocationEvent event) {
        redisTemplate.opsForValue().set("bus:" + event.getVehicleId(), event, java.time.Duration.ofSeconds(120));
    }

    @Override
    public Collection<BusLocationEvent> findAll() {
        Set<String> keys = redisTemplate.keys("bus:*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .map(redisTemplate.opsForValue()::get)
                .filter(Objects::nonNull)
                .filter(e -> e.getTimestamp() != null && com.dtc.bus_tracker.live.RealtimeSnapshot.fresh(e.getTimestamp()))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<BusLocationEvent> findByVehicleId(String vehicleId) {
        return Optional.ofNullable(redisTemplate.opsForValue().get("bus:" + vehicleId))
            .filter(e -> e.getTimestamp() != null && com.dtc.bus_tracker.live.RealtimeSnapshot.fresh(e.getTimestamp()));
    }
}
