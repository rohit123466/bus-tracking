package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.SeatType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SeatLayoutFactoryTest {

    private final SeatLayoutFactory factory = new SeatLayoutFactory();
    private final Bus bus = Bus.builder().vehicleId("TEST").build();

    @Test
    void lowFloorDemoLayoutHas35SeatsPlusOneWheelchairSpace() {
        List<Seat> seats = factory.generate(bus, new TicketingProperties.Layout(35, 4, 1));

        assertEquals(36, seats.size());
        assertEquals(1, seats.stream().filter(Seat::isWheelchairSpace).count());
        assertEquals(4, seats.stream().filter(s -> s.getSeatType() == SeatType.PRIORITY).count());
        assertEquals(List.of("01", "02", "03", "04"), seats.stream()
                .filter(s -> s.getSeatType() == SeatType.PRIORITY).map(Seat::getSeatNumber).toList());
        Seat wc = seats.stream().filter(Seat::isWheelchairSpace).findFirst().orElseThrow();
        assertEquals("WC1", wc.getSeatNumber());
        assertEquals(2, wc.getRowSpan());
        assertEquals(2, wc.getColumnSpan());
        assertUniqueNumbersAndCells(seats);
    }

    @Test
    void standardLayoutHasNoWheelchairSpace() {
        List<Seat> seats = factory.generate(bus, new TicketingProperties.Layout(32, 4, 0));
        assertEquals(32, seats.size());
        assertTrue(seats.stream().noneMatch(Seat::isWheelchairSpace));
        assertUniqueNumbersAndCells(seats);
    }

    @Test
    void layoutIsDeterministic() {
        TicketingProperties.Layout layout = new TicketingProperties.Layout(35, 4, 1);
        List<String> first = factory.generate(bus, layout).stream().map(this::describe).toList();
        List<String> second = factory.generate(bus, layout).stream().map(this::describe).toList();
        assertEquals(first, second);
    }

    private String describe(Seat s) {
        return s.getSeatNumber() + "@" + s.getRowIndex() + "," + s.getColumnIndex();
    }

    private void assertUniqueNumbersAndCells(List<Seat> seats) {
        Set<String> numbers = new HashSet<>();
        Set<String> cells = new HashSet<>();
        for (Seat s : seats) {
            assertTrue(numbers.add(s.getSeatNumber()), "duplicate seat number " + s.getSeatNumber());
            for (int r = s.getRowIndex(); r < s.getRowIndex() + s.getRowSpan(); r++) {
                for (int c = s.getColumnIndex(); c < s.getColumnIndex() + s.getColumnSpan(); c++) {
                    assertTrue(cells.add(r + ":" + c), "two seats share cell " + r + ":" + c);
                    assertTrue(c >= 0 && c < SeatLayoutFactory.COLUMNS);
                }
            }
        }
    }
}
