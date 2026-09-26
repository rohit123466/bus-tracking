package com.dtc.bus_tracker.dto;

import com.dtc.bus_tracker.entity.SeatType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SeatView {
    private Long seatId;
    private String seatNumber;
    private SeatType seatType;
    private int row;
    private int column;
    private int rowSpan;
    private int columnSpan;
    private SeatStatus status;
    /** Whether the requested passenger category may take this seat for the requested segment. */
    private boolean selectable;
    private String note;
    /** For OCCUPIED: the part of the requested segment the seat is taken for. */
    private String occupiedFromStop;
    private String occupiedToStop;
    /** For free seats a previous passenger already left: where they got down. */
    private String vacatedAtStop;
}
