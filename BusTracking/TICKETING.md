# Seat map, route-segment ticketing & occupancy monitoring (DEMO)

Adds a live seat map, conductor ticketing, a wheelchair space and Divyangjan ticket, and a
**simulated** camera-occupancy module on top of the existing tracker. All capacities, fares and
camera data are demo values, not official DTC figures, and no real computer vision is involved.

## Core idea: a ticket holds a seat for a route segment, not the whole trip

A ticket stores the trip stop sequence of its boarding and destination stops (from
`stop_times.stop_sequence`) and holds its seat for the half-open range `[from, to)`. Two journeys
conflict only if `newFrom < existingTo && newTo > existingFrom` (`util/RouteSegments`). So seat 05
sold A→C is occupied on A→B and B→C but free on C→D, C→E and D→F. There is no stored
`seat.status = BOOKED` anywhere: every seat state is computed per request from tickets
(`SeatAvailabilityService`).

When a passenger gets down (the conductor presses "Got down", or the bus reaches their
destination), the ticket becomes `COMPLETED` with `alighted_sequence`. From then on it only holds
`[from, alighted)`, so a seat vacated early is free for the rest of the route.

## Seat states (per requested segment and passenger category)

| Status | Meaning |
|---|---|
| AVAILABLE | Free regular seat for the whole segment |
| VACATED | Free, and an earlier passenger got down at or before this segment's boarding stop |
| OCCUPIED | An overlapping ticket holds it; the response says the exact sub-segment ("from B to C") |
| RESERVED | Free priority seat: Senior Citizen and Divyangjan tickets only |
| WHEELCHAIR | Free wheelchair space: Divyangjan tickets only |
| UNAVAILABLE | Seat is out of service |

## Database (Hibernate `ddl-auto=update`, as in the rest of the project)

The project has no Flyway/Liquibase. The schema comes from the JPA entities, and these changes
are additive (new nullable columns and new tables), so existing Postgres/H2 databases upgrade in
place.

- **Reused tables:** `routes`, `stops`, `trips`, `stop_times` hold the demo route and the stop
  sequence. Nothing duplicates route-stop ordering.
- **`buses`:** adds `bus_type` (`LOW_FLOOR` / `STANDARD`, null for live-feed buses) and
  `camera_installed`.
- **`trips`:** adds `current_stop_sequence`, the stop the bus is at, advanced by the conductor.
- **New tables:**
  - `seats`: `bus_id`, `seat_number`, `seat_type` (`REGULAR` / `PRIORITY` / `WHEELCHAIR`), grid
    position and span, `in_service`. Unique on `(bus_id, seat_number)`.
  - `passengers`: `name`, `category` (`ADULT` / `SENIOR_CITIZEN` / `DIVYANGJAN`).
  - `tickets`: bus, trip, seat, passenger, from/to stop and sequence, `alighted_sequence`,
    category, `ticket_type`, fare, `status` (`ACTIVE` / `COMPLETED` / `CANCELLED`), `issued_at`,
    unique `ticket_number`, unique `request_id` (idempotency key).
  - `camera_occupancy`: bus, seat, `scan_number`, `stop_sequence`, `status` (`OCCUPIED` /
    `EMPTY`), `confidence`, `detected_at`, `source`.

## Configuration (`application.properties`, `ticketing.*`)

```
ticketing.low-floor.seats=35            ticketing.standard.seats=32
ticketing.low-floor.priority-seats=4    ticketing.standard.priority-seats=4
ticketing.low-floor.wheelchair-spaces=1 ticketing.standard.wheelchair-spaces=0
ticketing.fare.base / per-stop / senior-concession-percent / accessibility-fare   (demo fares)
ticketing.camera.simulated-mismatch-rate=0.08
```

Seats are generated from this configuration once per bus (`SeatLayoutFactory`) and persisted,
so seat numbers never change between page loads.

## API

**Public** (no login, like the rest of the rider API; never returns passenger names):

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/ticketing/buses` | Every ticketing bus with live occupancy and free places per hop |
| GET | `/api/ticketing/buses/{bus}` | The same summary for one bus |
| GET | `/api/ticketing/buses/{bus}/seats?fromStopId&toStopId&category` | Seat map, counts, sold-out flag and demo fare quote for a segment |

**Conductor** (`/api/conductor/**`, requires the admin JWT from `POST /api/auth/login`):

| Method | Path | Purpose |
|---|---|---|
| GET | `/passengers` | Passenger list |
| GET | `/buses/{bus}/tickets` | Tickets on the bus's current trip |
| POST | `/tickets` | Sell a ticket: `{busNumber, passengerId \| passengerName+category, fromStopId, toStopId, seatNumber?, requestId?}` |
| POST | `/tickets/{no}/complete?atStopId=` | Passenger got down; frees the seat from that stop (default: the current stop) |
| POST | `/tickets/{no}/cancel` | Void an active ticket |
| POST | `/buses/{bus}/advance` | Move the bus to its next stop; passengers whose destination it is get down automatically |
| GET | `/buses/{bus}/camera` | Latest camera scan compared with ticket occupancy, including mismatches |
| POST | `/buses/{bus}/camera/scan` | "Run seat scan" |
| POST | `/buses/{bus}/camera/observations` | Push observations `{observations:[{seatNumber,status,confidence?}], source?}`. Used for manual correction now, and is the hook for a real CV service |
| GET | `/buses/{bus}/live` | Live console view: trip, conductor, seats, standing area, seat-availability indicator, ticket sales |
| POST | `/buses/{bus}/camera/standing-frames` | A camera / IoT device pushes one standing-area frame `{sequence?, capturedAt?, source?, detections:[{trackId, role?: PASSENGER\|CONDUCTOR, confidence?}]}`. Returns `APPLIED`, `DUPLICATE` or `OUT_OF_ORDER` |
| PUT | `/buses/{bus}/camera/preset` | `{presetId}` selects the standing-area preset played on the bus; `null` pauses the preset feed |
| POST | `/demo/reset` | Restore the deterministic preset data |

### Sale validation (`TicketService.sell`)

A sale is rejected with a specific message when:
- the bus doesn't exist (404);
- the seat doesn't belong to the bus;
- a stop doesn't exist or isn't on the route;
- the destination is not after the boarding stop;
- the bus has already left the boarding stop;
- the seat is out of service;
- a non-Divyangjan ticket asks for the wheelchair space, or an Adult asks for a priority seat
  (403);
- the passenger already holds an overlapping ticket;
- the segment is sold out for that category ("Sold Out for this route segment (A → C)");
- the bus is at capacity on any hop;
- the seat overlaps an existing ticket ("Seat 05 is already occupied from B to C.");
- the `requestId` was already used on a different bus.

Resubmitting the same `requestId` on the same bus (a double click, or a retry after a timeout)
issues nothing new: it returns the original ticket with HTTP 200 and `replayed: true`. The check
runs under the trip lock, so concurrent duplicates are caught too.

Sales lock the trip row (`SELECT … FOR UPDATE`), so concurrent sales can't double-book a seat.

## Wheelchair space & Divyangjan ticket

- Low-floor buses get one dedicated ♿ wheelchair space (`WC1`, seat type `WHEELCHAIR`, a 2×2 bay
  by the front door).
- It is part of the bus's capacity but is never a normal seat. Only a
  `Person with Disability / Divyangjan` ticket can take it, and auto-assignment never picks it.
- A Divyangjan passenger may also take priority seats. Their ticket type is **Divyangjan /
  Accessibility ticket**, or **… – Wheelchair Space** when they use `WC1`, and is priced at the
  configurable **Accessibility fare**.
- There is no Student category because the existing system has none.

## Camera occupancy (simulated)

`CameraOccupancyProvider` is the integration point. The only implementation,
`SimulatedCameraOccupancyProvider`, starts from ticket occupancy at the current stop and flips a
configurable share of seats using a seeded (reproducible) random generator. A real computer-vision
backend would implement the same interface, or push readings to `/camera/observations`.

Camera readings are stored separately from tickets. `CameraMonitoringService` compares the two and
reports:
- `TICKETED_BUT_EMPTY`: a ticket says occupied but the camera sees the seat empty;
- `OCCUPIED_WITHOUT_TICKET`: the camera sees someone but no ticket covers the seat.

The panel tags these readings **Preset feed** while they come from the simulator.

## Standing-area monitoring & seat availability

Camera buses also watch a designated **standing area**. The responsibilities are kept separate:

| Concern | Class |
|---|---|
| Detection: who the camera sees, with a track id and role | `CameraOccupancyProvider.nextStandingFrame` (preset feed) or `POST …/standing-frames` (device) |
| Continuous polling of the feed, every `feed-interval-ms` | `CameraFeedScheduler` |
| Who counts as a standing passenger | `StandingZoneTracker` (one per bus, held by `StandingAreaMonitor`) |
| AVAILABLE / NOT AVAILABLE | `SeatAvailabilityIndicator` |
| Ticket sales | `TicketService` (unchanged rules) |

`StandingZoneTracker` rules:
- **Counted once.** People are keyed by track id. The same passenger in consecutive frames, or twice
  in one frame, is one person.
- **Conductor excluded.** A track reported as `CONDUCTOR` is never counted. The flag is sticky: if a
  later frame mislabels the same track as a passenger, it still isn't counted. A counted track that
  is later identified as the conductor stops counting immediately.
- **Debounced.** A track counts after `confirm-frames` consecutive sightings and is dropped after
  `release-frames` consecutive misses. One false detection or a short occlusion does not flip the
  status.
- **Ordered.** A frame whose `sequence` (or `capturedAt`) is not newer than the last one from the
  same source is ignored as `DUPLICATE` / `OUT_OF_ORDER`. A new source, or a reconnect after an
  outage, starts a fresh numbering.
- **Fail-safe.** With no frame for `signal-timeout-ms`, the camera reads `DISCONNECTED` and the last
  confirmed state is held and marked stale. Nothing is guessed.

`SeatAvailabilityIndicator` reports **NOT AVAILABLE** when any passenger is standing (people stand
only when they cannot sit) or when every seat on the current hop is ticketed. Otherwise it reports
**AVAILABLE**. Without a camera, or before the first reading, it falls back to tickets alone. Ticket
sales never change the standing count; they are only logged next to the camera events.

Preset scenarios live in `src/main/resources/camera/standing-presets.json`
(`ticketing.camera.presets-location`). Each one is a list of frames: `detections`, `"signal": false`
(camera silent) or `"resend": true` (the previous frame again). Shipped presets: standing area clear,
passenger standing, conductor issuing tickets, passengers entering and leaving, camera disconnects
and reconnects, and a combined regular-service run. DTC-101 starts on the regular-service run and
DTC-102 on standing area clear. The console's **Source** selector switches presets, or pauses the
feed so a device can push frames. Standing-area state is in memory and rebuilds from the next frames
after a restart.

## Preset data (`TicketingDemoDataService`, seeded after the GTFS import)

- **Route `AF-1`** (was `DEMO-AF`; existing databases are renamed on startup): stops A · Kashmere Gate → B · Civil Lines → C · Mall Road → D · Azadpur →
  E · Shalimar Bagh → F · Pitampura. The stops are not added to `route_stop`, so the journey
  planner never suggests the fictional route to riders.
- **DTC-101** (low floor, camera):
  - #1001 Rahul, seat 05, A→C
  - #1002 Amit, seat 07, B→E
  - #1003 Neha, seat 05, C→E
  - #1004 Suresh Pal (Divyangjan), WC1, A→D
  - plus 14 more tickets
  - The seeded camera scan disagrees on seat 05 (empty) and seat 12 (occupied).
- **DTC-102** (low floor, camera): lightly booked. Seat 05 and WC1 are free along the whole route,
  so it is the bus to run tests on.
- **DTC-103** (standard, no wheelchair space, no camera): sold out A→C; exactly seats 05–12 are
  free C→E.

Tickets are seeded through the same `sell()` validation the conductor uses.

## Run

```
cd backend  &&  ./mvnw spring-boot:run "-Dspring-boot.run.profiles=demo"   # http://localhost:8080
cd frontend &&  npm run dev                                                 # http://localhost:5173
```

- Pages: `/seats` (passenger seat map) and `/conductor` (conductor console).
- The console uses the admin login: `ADMIN_USERNAME` / `ADMIN_PASSWORD`, which default to
  `admin` / `admin123` for local development only.

## Demo script

Log in at `/conductor` and press **Restore preset data** first.

1. **TEST 1** – DTC-102, new passenger, Adult, A→C, seat 05 → ticket issued.
2. **TEST 2** – B→D, seat 05 → "Seat 05 is already occupied from B · Civil Lines to C · Mall Road."
3. **TEST 3** – C→E, seat 05 → issued; the seat shows ↺ *vacated at C*.
4. **TEST 4** – D→F, seat 05 → succeeds against the A→C ticket alone. It overlaps TEST 3's C→E
   ticket on D→E, so cancel TEST 3's ticket first. With both held, it is correctly rejected with
   "occupied from D to E".
5. **TEST 5** – Adult, seat WC1 → rejected: wheelchair space is Divyangjan-only.
6. **TEST 6** – Person with Disability / Divyangjan, WC1, A→D → *Divyangjan / Accessibility
   ticket – Wheelchair Space*.
7. **TEST 7** – DTC-103, A→C → "Sold Out for this route segment (A → C)".
8. **TEST 8** – DTC-103, C→E → 8 free seats, and seat 05 shows as vacated.
9. **TEST 9** – DTC-101 camera panel → "Occupancy mismatch detected: ticket #1001 says Seat 05 is
   occupied … but the camera sees it empty."
10. **Try the journey:** on DTC-101, press **Move to B** then **Move to C**. Rahul and others get
    down automatically, and seat 05 now shows Neha (C→E). Press **Run seat scan** for a
    fresh scan at C.
11. **Standing area:** on DTC-102, set **Source** to each preset in turn. *Standing area clear* →
    EMPTY / AVAILABLE. *Passenger standing* → OCCUPIED / NOT AVAILABLE, 1 standing. *Conductor
    issuing tickets* → the conductor shows as in the area but the count stays 0 and seats stay
    AVAILABLE; issue a ticket meanwhile, and double-click **Issue ticket**, which still issues only
    one ticket. *Camera disconnects and reconnects* → DISCONNECTED with the last reading held.

Automated checks for the standing area: `StandingZoneTrackerTest` (25 rules and edge cases) and
`CameraPresetPlaybackTest` (every shipped preset played through the tracker).

Automated checks:
- `SeatAvailabilityServiceTest` covers the overlap rules, vacating, early alighting, wheelchair and
  priority rules, and per-segment sold-out.
- `SeatLayoutFactoryTest` covers the 35 + 1 layout and determinism.
