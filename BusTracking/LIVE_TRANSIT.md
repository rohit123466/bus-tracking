# Live transit upgrade

## Root causes found in main

- HomePage selected a route overlay but continued showing the location-limited nearby bus response. Every GPS update also triggered a fetch, in addition to polling.
- The legacy `Route.routeCode` holds GTFS `route_id`, not a public number. All 2,403 bundled routes have an empty `route_short_name`. Public 534 is represented by `534UP`/`534DOWN` in `route_long_name`; internal ID `534` belongs to `221UP`.
- The legacy import restricts the database geographically. Searching its records cannot cover all Delhi vehicles. Stops were also loaded using a single 1,000-record page.
- The realtime adapter discarded trip descriptors and generated wheelchair availability from a vehicle-ID hash. Static accessibility was then confused with live space availability.
- Existing ETA helpers substituted 20 km/h for stopped/missing-speed vehicles. The legacy journey planner omits waiting time; it is not used for the new recommendations.
- The bundled archive contains no `shapes.txt`; its calendar ends on 2025-01-01. It cannot support trustworthy current scheduled departures or actual route geometry by itself.
- Maven required Java 21, while the execution environment initially supplied Java 17. Maven did not inherit the environment HTTP proxy. Springdoc 2.8.5 was incompatible with the project's Spring Boot 4 generation. The context test depended on a local PostgreSQL server.

## Implementation and contracts

The existing React/Vite/Leaflet app, legacy numeric-ID APIs, ticketing and admin pages remain. New endpoints are under `/api/transit` and use **GTFS string IDs**. Do not pass legacy database primary keys to them.

| GET path | Parameters | Result |
|---|---|---|
| `/api/transit/buses/live` | `routeNumber` optional | `buses`, `warnings`, `fetchedAt` |
| `/api/transit/routes/search` | `query` | Public route numbers, prefix autocomplete |
| `/api/transit/routes/map` | `routeNumber` | Stops and actual GTFS shape segments, when present |
| `/api/transit/stops/search` | `query`, optional `routeNumber` | Up to 200 matching stops; refine the query |
| `/api/transit/stops/{stopId}/arrivals` | GTFS stop ID | Approaching arrivals and data-quality warnings |
| `/api/transit/routes/alternatives` | `sourceStopId`, `destinationStopId`, optional `routeNumber` | Comparison status, baseline and faster options |
| `/api/transit/stops/nearest` | `latitude`, `longitude` | Verified walking route or labeled straight-line fallback |
| `/api/transit/navigation/walking` | `startLat`, `startLon`, `stopId` | Pedestrian geometry and provider maneuvers |

Coordinate endpoints validate finite coordinates in Delhi/NCR. Unknown stops return 404. Client requests are cancelable, errors are displayed, and polling schedules the next request after the preceding request finishes (10-second delay; no overlapping requests). Alternatives are evaluated at most once per 30 seconds for the current selection. Changing route, stop or destination cancels obsolete requests.

### Route matching

The full GTFS archive is indexed once at startup, independently of the regional relational import. Matching is exact after trim/uppercase: `534` does not include `534E`. A nonempty `route_short_name` is authoritative. The documented Delhi-feed fallback accepts number-like `route_long_name` values ending in `UP` or `DOWN`, stripping only that suffix. It never treats a raw internal route ID as the public number. Unknown or conflicting mappings fail closed with a warning.

Realtime `trip_id` resolves through static trips to routes. A route-only descriptor can still be filtered accurately, but cannot produce an ETA without a reliable trip/stop progression. Conflicting trip/route descriptors are excluded. Both directions are shown for public-number search, with separate stable vehicle-ID markers. Feed snapshots replace, rather than append to, the current vehicle collection. Positions older than 120 seconds or more than 30 seconds in the future are excluded.

### Arrival prediction

Fresh absolute GTFS Realtime TripUpdate arrival times take precedence. Skipped, canceled, past and NO_DATA predictions are handled explicitly; stale updates are not reused. The implementation does not infer departure times from the expired bundled calendar or convert delay-only updates into unsupported current predictions.

Otherwise, when a trip has a shape, the vehicle and target stop are projected onto it. Remaining distance is the forward polyline arc length. Off-shape fixes over 100 m, passed stops and contradictory movement bearings are rejected. Feed speed is converted from m/s to km/h; a missing/zero speed may be derived from successive positions at least five seconds apart, with plausibility limits. Geometry ETA is explicitly an estimate using recent speed, without an invented traffic or dwell-time allowance. Stationary/missing-speed vehicles receive no numeric prediction. Repeated-stop loops without unambiguous progression are conservative/unavailable. Route-only descriptors are counted as unresolved for arrivals.

### Alternatives

The new planner evaluates direct and one-transfer journeys using fresh arrival predictions for the same vehicle/trip at boarding and alighting stops. This preserves destination order and direction. It evaluates the selected stop plus up to four nearby stops within 800 m, using verified pedestrian times for changed boarding stops. It includes walking, initial waiting, ride time, a two-minute minimum interchange allowance, and the second bus's actual predicted wait. A 30-second boarding margin is required.

Evaluation is triggered when the earliest displayed prediction exceeds 15 minutes. The server also checks that a destination-serving baseline actually exceeds the threshold. A destination is requested if absent. An alternative must improve total journey time by **at least three minutes and ten percent**. If a complete baseline or alternative cannot be timed, no unsupported recommendation is emitted. Search is limited to one transfer and does not claim a globally optimal itinerary. Historical travel-time data is not available in this repository.

### Walking provider

OpenRouteService `POST /v2/directions/foot-walking/geojson` runs server-side; the API key is never sent to React. Provider geometry and instructions are used verbatim. Driving profiles are rejected. Nearby stops use a spatial grid. Candidates within 2 km are ordered by straight-line lower bounds, then walking distances are compared; evaluation stops when the next lower bound cannot beat the best route. At most eight external routes are checked. Partial searches are explicitly labeled “closest verified” rather than claiming a global nearest stop.

A bounded 30-second in-memory cache, timeouts and a failure cooldown limit provider calls. GPS is not persisted. `watchPosition()` starts only on button click and is cleared on stop/unmount. Navigation pauses directions on poor accuracy (>50 m), updates remaining polyline distance locally, detects deviation above max(35 m, twice GPS accuracy), and reroutes at most every 30 seconds. Missing provider credentials/errors return straight-line distance with reachability unknown, no walking ETA and no fabricated turns.

## Configuration

| Variable | Purpose |
|---|---|
| `DTC_API_KEY` | Required for Delhi vehicle feed; blank means explicit unavailable state |
| `DTC_TRIP_UPDATES_URL` | Optional complete authenticated GTFS-RT TripUpdates URL; leave blank if unavailable |
| `GTFS_RESOURCE` | Optional Spring resource, e.g. `file:C:/data/GTFS.zip`; defaults to bundled archive |
| `ORS_API_KEY` | OpenRouteService API key; optional, enables real pedestrian routing |
| `ORS_WALKING_URL` | Optional pedestrian endpoint; defaults to official `foot-walking/geojson` URL |
| `VITE_API_URL` | Frontend API base, normally `http://localhost:8080/api` |
| `JWT_SECRET`, `ADMIN_USERNAME`, `ADMIN_PASSWORD` | Existing security settings for admin/conductor |

Use a GTFS static feed and realtime feed from compatible versions. Updating the static archive requires a backend restart. The new full-feed index is in memory and does not alter/migrate existing route IDs or ticket data. `GTFS_RESOURCE` affects the new live index; legacy database import remains separately configured. `--gtfs.import.enabled=false` can skip that legacy import for live-API development. Do not use that option if you need a newly populated legacy route database.

## Windows development and Maven repair

Install JDK 21, Node.js 22.12+ (or Node 24), Git and Git LFS. No Docker, WSL or Linux is required.

```powershell
git clone https://github.com/rohit123466/bus-tracking.git
cd bus-tracking
git lfs pull
cd BusTracking\backend
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21' # your actual installed JDK path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -version
.\scripts\build-windows.ps1
$env:DTC_API_KEY = 'your-key'
$env:ORS_API_KEY = 'your-key'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=demo'
```

The demo profile uses H2 and an in-memory position store; PostgreSQL/Redis are not required locally. Existing ticketing demo data remains labeled as demo. First-time legacy import can take time. For just the new full-feed live API, add `'-Dspring-boot.run.arguments=--gtfs.import.enabled=false'`.

In a second PowerShell window:

```powershell
cd bus-tracking\BusTracking\frontend
npm ci
npm test
npm run lint
npm run build
npm run dev
```

Open the Vite URL. Geolocation requires localhost or HTTPS and browser permission.

Maven troubleshooting, based on errors actually encountered during this implementation:

1. `release version 21 not supported`: check `.\mvnw.cmd -version`, then set `JAVA_HOME` and your IDE Maven runner JDK to 21. Do not downgrade the POM to hide the mismatch.
2. `Temporary failure in name resolution` / unresolved parent: check `Resolve-DnsName repo.maven.apache.org` and `Test-NetConnection repo.maven.apache.org -Port 443`. Maven does not automatically consume `HTTPS_PROXY`. If your environment requires one, run `.\scripts\build-windows.ps1 -UseEnvironmentProxy`; it creates temporary settings from your existing proxy environment, then deletes them. Authenticated proxies should be configured in your private `%USERPROFILE%\.m2\settings.xml`.
3. `PKIX path building failed`: the JDK used by Maven must trust your organization's approved proxy/root CA. Import that CA into an appropriate truststore or use an administrator-provided truststore through `MAVEN_OPTS`. Never disable TLS certificate verification.
4. After fixing connectivity, `.\mvnw.cmd -U verify` retries failed dependency resolution. There is no need to delete the entire Maven cache.
5. Springdoc is now 3.1.1, compatible with Spring Boot 4. Mockito uses the subclass mock maker for these tests, avoiding dynamic-agent attachment requirements.

The PowerShell helper was reviewed but not executed on Windows in this environment. Maven itself was executed with JDK 21 and a local proxy/truststore configuration; no environment-specific proxy or truststore is committed.

## Verification

See the final verification section appended below for executed results. All automated realtime/GPS/provider scenarios are **simulated**. No real DTC GPS session, external pedestrian route, or on-street walking session has been verified. The bundled static archive was inspected directly.

Run optional browser tests with Vite started automatically by the script:

```powershell
npm install --no-save playwright
npx playwright install chromium
node tests/browser.mjs
```

## Limits requiring real data / operational follow-up

- The bundled missing shapes and expired calendar cannot be repaired by inventing route geometry or schedules. Supply updated official GTFS plus TripUpdates or shapes to enable dependable predictions.
- Differential realtime feeds, delay-only predictions, frequency-service instance resolution, multi-transfer planning and historical traffic modeling are not implemented. Ambiguous predictions are unavailable.
- The legacy `/journey/plan` page remains a heuristic planner; the new live home-page recommendation engine does not use its fixed-speed assumptions.
- GTFS trip/stop-time indexing consumes memory proportional to feed size. It avoids per-request database scans but needs deployment capacity planning.
- OpenRouteService coverage, plan quotas and map correctness affect routing; nearby-stop search limits are visible to users.
- Browser tests validate mock GPS behavior, not physical GPS accuracy. Navigation is advisory.

## Executed verification results

- Maven verify: passed; 30 tests, zero failures/errors, packaged executable JAR.
- Frontend: npm test (4 tests), npm run lint and npm run build passed.
- Browser: 8 simulated API/GPS scenarios passed before the environment reset.
- Runtime smoke check after restart: React/Vite → proxy → running Spring Boot → actual bundled GTFS returned public 534, 534A, 534C and 534E; route 534 returned 215 stops. No live-feed credential was configured.
- No live DTC feed or on-street pedestrian navigation verification is claimed.

## Exact changed files

- `BusTracking/LIVE_TRANSIT.md`
- `BusTracking/backend/pom.xml`
- `BusTracking/backend/scripts/build-windows.ps1`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/BusTrackerApplication.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/AlternativeService.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/Geometry.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/LiveTransitService.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/RealtimeSnapshot.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/TransitController.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/TransitIndex.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/WalkingService.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/BusLocationIngestService.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/DtcApiClient.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/DtcIngestionService.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/PassingBusService.java`
- `BusTracking/backend/src/main/java/com/dtc/bus_tracker/util/EtaCalculator.java`
- `BusTracking/backend/src/main/resources/application-demo.properties`
- `BusTracking/backend/src/main/resources/application.properties`
- `BusTracking/backend/src/test/java/com/dtc/bus_tracker/BusTrackerApplicationTests.java`
- `BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/LegacyEtaTest.java`
- `BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/LiveTransitTest.java`
- `BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/WalkingServiceTest.java`
- `BusTracking/backend/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
- `BusTracking/frontend/package.json`
- `BusTracking/frontend/src/App.jsx`
- `BusTracking/frontend/src/api/client.js`
- `BusTracking/frontend/src/components/BusMap.jsx`
- `BusTracking/frontend/src/components/SearchBar.jsx`
- `BusTracking/frontend/src/components/TransitStopPicker.jsx`
- `BusTracking/frontend/src/components/WheelchairBadge.jsx`
- `BusTracking/frontend/src/hooks/useWalkingNavigation.js`
- `BusTracking/frontend/src/pages/BusSearchPage.jsx`
- `BusTracking/frontend/src/pages/HomePage.jsx`
- `BusTracking/frontend/src/pages/LiveTransit.css`
- `BusTracking/frontend/src/pages/PassingNearMePage.jsx`
- `BusTracking/frontend/src/pages/StopsNearbyPage.jsx`
- `BusTracking/frontend/src/utils/navigation.js`
- `BusTracking/frontend/src/utils/wheelchairStatus.js`
- `BusTracking/frontend/tests/browser.mjs`
- `BusTracking/frontend/tests/navigation.test.js`
- `BusTracking/frontend/vite.config.js`
- `README.md`
