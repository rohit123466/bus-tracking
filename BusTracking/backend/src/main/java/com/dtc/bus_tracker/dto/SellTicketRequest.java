package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.PassengerCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Conductor ticket sale. Either passengerId (existing passenger) or
 * passengerName (walk-in, created with {@code category}) is required.
 * seatNumber is optional - omitted, the best free seat for the category is
 * assigned (never the wheelchair space, which must be chosen explicitly).
 */
@Getter
@Setter
public class SellTicketRequest {
    @NotBlank
    private String busNumber;

    private Long passengerId;

    @Size(max = 80)
    private String passengerName;

    /** Defaults to the existing passenger's registered category. */
    private PassengerCategory category;

    @NotNull
    private Long fromStopId;

    @NotNull
    private Long toStopId;

    @Size(max = 10)
    private String seatNumber;

    /** Client-generated idempotency key; resubmitting it never issues a second ticket. */
    @Size(max = 64)
    private String requestId;
}
