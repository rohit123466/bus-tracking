package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.PassengerCategory;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PassengerDto {
    private Long id;
    private String name;
    private PassengerCategory category;
    private String categoryLabel;
}
