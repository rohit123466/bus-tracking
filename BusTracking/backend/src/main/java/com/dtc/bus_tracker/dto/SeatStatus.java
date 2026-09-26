package com.dtc.bus_tracker.dto;

/** A seat's state for one requested route segment (not a stored column). */
public enum SeatStatus {
    /** Free regular seat for the whole segment. */
    AVAILABLE,
    /** Free for the whole segment because an earlier passenger got down at or before its start. */
    VACATED,
    /** An overlapping ticket holds it for at least one hop of the segment. */
    OCCUPIED,
    /** Free priority seat (Senior Citizen / Divyangjan tickets only). */
    RESERVED,
    /** Free dedicated wheelchair space (Divyangjan tickets only). */
    WHEELCHAIR,
    /** Out of service; never sold. */
    UNAVAILABLE
}
