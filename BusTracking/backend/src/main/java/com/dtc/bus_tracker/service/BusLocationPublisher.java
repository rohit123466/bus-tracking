package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.dto.BusLocationEvent;

import java.util.List;

public interface BusLocationPublisher {
    void publish(BusLocationEvent event);

    /** One feed poll's worth of events; implementations may write them as a single batch. */
    default void publishAll(List<BusLocationEvent> events) {
        events.forEach(this::publish);
    }
}
