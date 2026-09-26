package com.dtc.bus_tracker.entity;

public enum PassengerCategory {
    ADULT("Adult"),
    SENIOR_CITIZEN("Senior Citizen"),
    DIVYANGJAN("Person with Disability / Divyangjan");

    private final String label;

    PassengerCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean canUse(SeatType seatType) {
        return switch (seatType) {
            case REGULAR -> true;
            case PRIORITY -> this == SENIOR_CITIZEN || this == DIVYANGJAN;
            case WHEELCHAIR -> this == DIVYANGJAN;
        };
    }
}
