package com.dtc.bus_tracker.entity;

public enum TicketType {
    GENERAL("General ticket"),
    SENIOR_CONCESSION("Senior Citizen ticket"),
    ACCESSIBILITY("Divyangjan / Accessibility ticket"),
    ACCESSIBILITY_WHEELCHAIR("Divyangjan / Accessibility ticket - Wheelchair Space");

    private final String label;

    TicketType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static TicketType of(PassengerCategory category, SeatType seatType) {
        return switch (category) {
            case ADULT -> GENERAL;
            case SENIOR_CITIZEN -> SENIOR_CONCESSION;
            case DIVYANGJAN -> seatType == SeatType.WHEELCHAIR ? ACCESSIBILITY_WHEELCHAIR : ACCESSIBILITY;
        };
    }
}
