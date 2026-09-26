package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.SeatType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a bus's seats from its type's configured layout. Deterministic: the
 * same configuration always produces the same seat numbers and positions,
 * and seats are persisted once, so seat "05" is always the same seat.
 *
 * Grid is 5 columns wide with column 2 as the aisle. Indian buses are
 * right-hand drive with doors on the left, so wheelchair bays sit at the
 * front-left by the door (2 rows x 2 columns each), the partial row sits
 * opposite the rear door, and the back row spans the aisle.
 */
@Component
public class SeatLayoutFactory {

    public static final int COLUMNS = 5;
    public static final int AISLE_COLUMN = 2;
    private static final int[] ROW_COLUMNS = {0, 1, 3, 4};
    private static final int[] PARTIAL_ROW_COLUMNS = {3, 4, 1, 0};
    private static final int BACK_ROW_SEATS = 5;

    public List<Seat> generate(Bus bus, TicketingProperties.Layout layout) {
        List<Seat> seats = new ArrayList<>();
        int total = layout.getSeats();
        int[] numbered = {0};
        int row = 0;

        for (int w = 1; w <= layout.getWheelchairSpaces(); w++) {
            seats.add(place(bus, "WC" + w, SeatType.WHEELCHAIR, row, 0, 2, 2));
            for (int r = row; r < row + 2; r++) {
                for (int col = 3; col <= 4 && numbered[0] < total; col++) {
                    seats.add(seat(bus, layout, numbered, r, col));
                }
            }
            row += 2;
        }

        int remaining = total - numbered[0];
        if (remaining > BACK_ROW_SEATS) {
            int fullRows = (remaining - BACK_ROW_SEATS) / ROW_COLUMNS.length;
            int partial = (remaining - BACK_ROW_SEATS) % ROW_COLUMNS.length;
            for (int i = 0; i < fullRows; i++, row++) {
                for (int col : ROW_COLUMNS) seats.add(seat(bus, layout, numbered, row, col));
            }
            if (partial > 0) {
                for (int i = 0; i < partial; i++) seats.add(seat(bus, layout, numbered, row, PARTIAL_ROW_COLUMNS[i]));
                row++;
            }
        }

        remaining = total - numbered[0];
        if (remaining == BACK_ROW_SEATS) {
            for (int col = 0; col < COLUMNS; col++) seats.add(seat(bus, layout, numbered, row, col));
        } else {
            for (int i = 0; i < remaining; i++) seats.add(seat(bus, layout, numbered, row, ROW_COLUMNS[i]));
        }
        return seats;
    }

    /** Next numbered seat; the first {@code prioritySeats} numbers (nearest the front) are priority seats. */
    private Seat seat(Bus bus, TicketingProperties.Layout layout, int[] numbered, int row, int col) {
        numbered[0]++;
        SeatType type = numbered[0] <= layout.getPrioritySeats() ? SeatType.PRIORITY : SeatType.REGULAR;
        return place(bus, String.format("%02d", numbered[0]), type, row, col, 1, 1);
    }

    private Seat place(Bus bus, String number, SeatType type, int row, int col, int rowSpan, int colSpan) {
        return Seat.builder()
                .bus(bus)
                .seatNumber(number)
                .seatType(type)
                .rowIndex(row)
                .columnIndex(col)
                .rowSpan(rowSpan)
                .columnSpan(colSpan)
                .inService(true)
                .build();
    }
}
