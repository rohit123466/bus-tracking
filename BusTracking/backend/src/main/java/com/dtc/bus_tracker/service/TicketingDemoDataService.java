package com.dtc.bus_tracker.service;

import com.dtc.bus_tracker.config.TicketingProperties;
import com.dtc.bus_tracker.dto.SellTicketRequest;
import com.dtc.bus_tracker.entity.Bus;
import com.dtc.bus_tracker.entity.BusStatus;
import com.dtc.bus_tracker.entity.BusType;
import com.dtc.bus_tracker.entity.CameraSeatStatus;
import com.dtc.bus_tracker.entity.PassengerCategory;
import com.dtc.bus_tracker.entity.Route;
import com.dtc.bus_tracker.entity.Seat;
import com.dtc.bus_tracker.entity.Stop;
import com.dtc.bus_tracker.entity.StopTime;
import com.dtc.bus_tracker.entity.Ticket;
import com.dtc.bus_tracker.entity.Trip;
import com.dtc.bus_tracker.entity.TripStatus;
import com.dtc.bus_tracker.repository.BusRepository;
import com.dtc.bus_tracker.repository.CameraOccupancyRepository;
import com.dtc.bus_tracker.repository.PassengerRepository;
import com.dtc.bus_tracker.repository.RouteRepository;
import com.dtc.bus_tracker.repository.SeatRepository;
import com.dtc.bus_tracker.repository.StopRepository;
import com.dtc.bus_tracker.repository.StopTimeRepository;
import com.dtc.bus_tracker.repository.TicketRepository;
import com.dtc.bus_tracker.repository.TripRepository;
import com.dtc.bus_tracker.service.CameraOccupancyProvider.SeatObservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic demo data for seat-level ticketing: one demo route A&rarr;F
 * (as a route + trip + stop_times, like GTFS data), three buses with
 * persisted seats, and tickets that exercise route-segment occupancy.
 *
 * Tickets are issued through {@link TicketService#sell} - the same
 * validation the conductor uses - so the seed itself proves e.g. that
 * Neha's C&rarr;E ticket on seat 05 is legal after Rahul's A&rarr;C one.
 *
 * Seeding runs on ApplicationReadyEvent, i.e. after the GTFS import runner:
 * GtfsImportService skips importing when any route exists, so the demo route
 * must never be created first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketingDemoDataService {

    public static final String ROUTE_CODE = "DEMO-AF";
    private static final String SEED_USER = "demo-seed";

    private record DemoStop(String stopId, String name, double lat, double lng) {
    }

    private record DemoBus(String number, BusType type, boolean camera) {
    }

    private record DemoTicket(String passenger, PassengerCategory category, String seat, char from, char to) {
    }

    // A plausible North Delhi corridor; names carry their A-F letter so
    // tickets and error messages read like the spec ("from B to C").
    private static final List<DemoStop> STOPS = List.of(
            new DemoStop("DEMO-A", "A · Kashmere Gate", 28.6675, 77.2282),
            new DemoStop("DEMO-B", "B · Civil Lines", 28.6811, 77.2227),
            new DemoStop("DEMO-C", "C · Mall Road", 28.6946, 77.2090),
            new DemoStop("DEMO-D", "D · Azadpur", 28.7075, 77.1800),
            new DemoStop("DEMO-E", "E · Shalimar Bagh", 28.7165, 77.1636),
            new DemoStop("DEMO-F", "F · Pitampura", 28.7033, 77.1320));

    private static final List<DemoBus> BUSES = List.of(
            new DemoBus("DTC-101", BusType.LOW_FLOOR, true),
            new DemoBus("DTC-102", BusType.LOW_FLOOR, true),
            new DemoBus("DTC-103", BusType.STANDARD, false));

    private static final List<DemoTicket> DTC_101_TICKETS = List.of(
            new DemoTicket("Rahul", PassengerCategory.ADULT, "05", 'A', 'C'),
            new DemoTicket("Amit", PassengerCategory.ADULT, "07", 'B', 'E'),
            new DemoTicket("Neha", PassengerCategory.ADULT, "05", 'C', 'E'),
            new DemoTicket("Demo Passenger", PassengerCategory.DIVYANGJAN, "WC1", 'A', 'D'),
            new DemoTicket("Sunita Sharma", PassengerCategory.SENIOR_CITIZEN, "02", 'A', 'F'),
            new DemoTicket("Priya Verma", PassengerCategory.ADULT, "09", 'A', 'F'),
            new DemoTicket("Arjun Singh", PassengerCategory.ADULT, "10", 'A', 'B'),
            new DemoTicket("Karan Bhatia", PassengerCategory.ADULT, "10", 'B', 'E'),
            new DemoTicket("Kavita Rao", PassengerCategory.ADULT, "11", 'B', 'D'),
            new DemoTicket("Mohammed Irfan", PassengerCategory.ADULT, "13", 'A', 'E'),
            new DemoTicket("Ramesh Kumar", PassengerCategory.SENIOR_CITIZEN, "01", 'C', 'F'),
            new DemoTicket("Anjali Gupta", PassengerCategory.ADULT, "15", 'A', 'C'),
            new DemoTicket("Vikram Mehta", PassengerCategory.ADULT, "16", 'D', 'F'),
            new DemoTicket("Pooja Nair", PassengerCategory.DIVYANGJAN, "03", 'B', 'F'),
            new DemoTicket("Deepak Yadav", PassengerCategory.ADULT, "18", 'A', 'D'),
            new DemoTicket("Sneha Kapoor", PassengerCategory.ADULT, "19", 'A', 'B'),
            new DemoTicket("Meera Iyer", PassengerCategory.ADULT, "19", 'C', 'F'),
            new DemoTicket("Rohit Malhotra", PassengerCategory.ADULT, "20", 'B', 'F'));

    // DTC-102 is kept light (seat 05 and the wheelchair space free all
    // route) so the test scenarios can be run on it from a clean state.
    private static final List<DemoTicket> DTC_102_TICKETS = List.of(
            new DemoTicket("Meena Joshi", PassengerCategory.ADULT, "10", 'A', 'D'),
            new DemoTicket("Harish Chandra", PassengerCategory.SENIOR_CITIZEN, "04", 'B', 'E'),
            new DemoTicket("Tanvi Desai", PassengerCategory.ADULT, "12", 'C', 'F'),
            new DemoTicket("Farhan Ali", PassengerCategory.ADULT, "14", 'A', 'B'));

    private final RouteRepository routeRepository;
    private final StopRepository stopRepository;
    private final BusRepository busRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final PassengerRepository passengerRepository;
    private final CameraOccupancyRepository cameraOccupancyRepository;
    private final SeatLayoutFactory seatLayoutFactory;
    private final TicketingProperties properties;
    private final TicketService ticketService;
    private final TicketingJourneyLoader journeyLoader;
    private final SeatAvailabilityService seatAvailability;
    private final CameraMonitoringService cameraMonitoring;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            transactionTemplate.executeWithoutResult(status -> ensureRouteBusesAndSeats());
            transactionTemplate.executeWithoutResult(status -> {
                if (ticketRepository.count() == 0 && passengerRepository.count() == 0) {
                    seedTicketsAndCamera();
                    log.info("Seeded ticketing demo data (DTC-101, DTC-102, DTC-103 on route {}).", ROUTE_CODE);
                }
            });
        } catch (Exception e) {
            log.error("Ticketing demo seed failed", e);
        }
    }

    /**
     * Back to the deterministic starting state: all tickets, passengers and
     * camera scans removed, every demo bus back at stop A, demo tickets
     * re-issued. Seats, buses and the route are kept (they never change).
     */
    public void reset() {
        transactionTemplate.executeWithoutResult(status -> {
            ticketRepository.deleteAllInBatch();
            cameraOccupancyRepository.deleteAllInBatch();
            passengerRepository.deleteAllInBatch();
            ensureRouteBusesAndSeats();
            for (DemoBus demoBus : BUSES) {
                tripRepository.findByTripId(tripId(demoBus)).ifPresent(trip -> trip.setCurrentStopSequence(1));
            }
        });
        // Ticket numbers are 1000 + id; restart ids so the seed is #1001 again.
        for (String table : List.of("tickets", "passengers", "camera_occupancy")) {
            try {
                jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH 1");
            } catch (Exception e) {
                log.warn("Could not restart id sequence of {} ({}); ticket numbers will continue from the last one.",
                        table, e.getMessage());
            }
        }
        transactionTemplate.executeWithoutResult(status -> seedTicketsAndCamera());
    }

    private void ensureRouteBusesAndSeats() {
        Route route = routeRepository.findByRouteCode(ROUTE_CODE).orElseGet(() -> routeRepository.save(
                Route.builder().routeCode(ROUTE_CODE).name("[DEMO] Ticketing demo route A → F").build()));

        // The stops are deliberately NOT added to the route_stop join table:
        // the journey planner and "routes serving this stop" read that table,
        // and a fictional demo route shouldn't be suggested to real riders.
        // Order comes from the demo trips' stop_times instead.
        List<Stop> stops = new ArrayList<>();
        for (int i = 0; i < STOPS.size(); i++) {
            DemoStop d = STOPS.get(i);
            int sequence = i + 1;
            stops.add(stopRepository.findByStopId(d.stopId()).orElseGet(() -> stopRepository.save(Stop.builder()
                    .stopId(d.stopId())
                    .name(d.name())
                    .latitude(d.lat())
                    .longitude(d.lng())
                    .sequenceNumber(sequence)
                    .wheelchairBoarding(true)
                    .build())));
        }

        for (DemoBus demoBus : BUSES) {
            Bus bus = busRepository.findByVehicleId(demoBus.number()).orElseGet(() -> busRepository.save(Bus.builder()
                    .vehicleId(demoBus.number())
                    .route(route)
                    .status(BusStatus.ACTIVE)
                    .wheelchairAccessible(demoBus.type() == BusType.LOW_FLOOR)
                    .busType(demoBus.type())
                    .cameraInstalled(demoBus.camera())
                    .build()));

            if (!seatRepository.existsByBus_Id(bus.getId())) {
                seatRepository.saveAll(seatLayoutFactory.generate(bus, properties.layoutFor(demoBus.type())));
            }

            if (tripRepository.findByTripId(tripId(demoBus)).isEmpty()) {
                Trip trip = tripRepository.save(Trip.builder()
                        .tripId(tripId(demoBus))
                        .serviceId("DEMO")
                        .route(route)
                        .bus(bus)
                        .status(TripStatus.IN_PROGRESS)
                        .currentStopSequence(1)
                        .wheelchairAccessible(demoBus.type() == BusType.LOW_FLOOR)
                        .startTime(LocalDate.now().atTime(8, 0))
                        .build());
                for (int i = 0; i < stops.size(); i++) {
                    LocalTime time = LocalTime.of(8, 0).plusMinutes(8L * i);
                    stopTimeRepository.save(StopTime.builder()
                            .trip(trip)
                            .stop(stops.get(i))
                            .arrivalTime(time)
                            .departureTime(time)
                            .stopSequence(i + 1)
                            .build());
                }
            }
        }
    }

    private void seedTicketsAndCamera() {
        issue("DTC-101", DTC_101_TICKETS);
        issue("DTC-102", DTC_102_TICKETS);
        issue("DTC-103", standardBusTickets());

        // DTC-101's first scan disagrees with the tickets on purpose:
        // Rahul's seat 05 looks empty, and someone sits in unticketed seat 12.
        seedCameraScan("DTC-101", Map.of("05", CameraSeatStatus.EMPTY, "12", CameraSeatStatus.OCCUPIED));
        seedCameraScan("DTC-102", Map.of());
    }

    /**
     * DTC-103 (standard bus, no wheelchair space) is sold out A&rarr;C and has
     * exactly seats 05-12 left C&rarr;E, showing that "sold out" is per segment.
     */
    private List<DemoTicket> standardBusTickets() {
        int seats = properties.getStandard().getSeats();
        int priority = properties.getStandard().getPrioritySeats();
        List<DemoTicket> tickets = new ArrayList<>();
        int rider = 1;
        for (int seat = 1; seat <= seats; seat++) {
            tickets.add(rider(rider++, seat, priority, 'A', 'C'));
        }
        for (int seat = 1; seat <= seats; seat++) {
            if (seat >= 5 && seat <= 12) continue;
            tickets.add(rider(rider++, seat, priority, 'C', 'E'));
        }
        return tickets;
    }

    private DemoTicket rider(int n, int seat, int prioritySeats, char from, char to) {
        PassengerCategory category = seat <= prioritySeats ? PassengerCategory.SENIOR_CITIZEN : PassengerCategory.ADULT;
        return new DemoTicket(String.format("Demo Rider %02d", n), category, String.format("%02d", seat), from, to);
    }

    private void issue(String busNumber, List<DemoTicket> tickets) {
        for (DemoTicket t : tickets) {
            SellTicketRequest request = new SellTicketRequest();
            request.setBusNumber(busNumber);
            request.setPassengerName(t.passenger());
            request.setCategory(t.category());
            request.setSeatNumber(t.seat());
            request.setFromStopId(stopId(t.from()));
            request.setToStopId(stopId(t.to()));
            ticketService.sell(request, SEED_USER);
        }
    }

    private void seedCameraScan(String busNumber, Map<String, CameraSeatStatus> overrides) {
        TicketingJourney journey = journeyLoader.load(busNumber);
        Map<Long, Ticket> onBoard = seatAvailability.onBoardAt(journey, journey.currentSequence());
        Set<String> overridden = overrides.keySet();
        List<SeatObservation> observations = new ArrayList<>();
        for (Seat seat : journey.seats()) {
            CameraSeatStatus status = overrides.getOrDefault(seat.getSeatNumber(),
                    onBoard.containsKey(seat.getId()) ? CameraSeatStatus.OCCUPIED : CameraSeatStatus.EMPTY);
            observations.add(new SeatObservation(seat, status, overridden.contains(seat.getSeatNumber()) ? 0.71 : 0.94));
        }
        cameraMonitoring.storeScan(journey, observations, SimulatedCameraOccupancyProvider.SOURCE);
    }

    private Long stopId(char letter) {
        return stopRepository.findByStopId("DEMO-" + letter).orElseThrow().getId();
    }

    private static String tripId(DemoBus bus) {
        return "DEMO-TRIP-" + bus.number();
    }
}
