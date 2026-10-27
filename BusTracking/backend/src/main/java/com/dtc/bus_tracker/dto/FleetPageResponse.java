package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.service.BusLiveStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class FleetPageResponse {
    private List<FleetBusResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    /** Buses per live status among those matching q and route (before the status filter), for filter chips. */
    private Map<BusLiveStatus, Long> statusCounts;
    /** Epoch seconds the live statuses were computed at. */
    private long generatedAt;
}
