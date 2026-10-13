# Final verification — Delhi DTC Bus Tracker

Date: 2026-10-13 (Asia/Calcutta). Base: `main` at `aa7e3eaf029ebd60303556d414d52cc279e88903` (merged PR #21). Branch: `fix/complete-dtc-live-tracking`.

**Overall: PARTIAL — local implementation and verification completed; authenticated DTC ingestion is verified in the follow-up below; real arrival accuracy and production deployment remain unverified.** Fixture predictions, browser GPS and ticket occupancy are not evidence of real-world transit accuracy.

## Status definitions

- COMPLETE — Implemented and verified within the stated scope.
- PARTIAL — Implemented but not fully verified.
- BLOCKED — Requires unavailable external data, credentials or infrastructure.
- FAILED — Tested and still failing.

## Already implemented before this task

Reviewed merged PRs [#21](https://github.com/rohit123466/bus-tracking/pull/21) and [#19](https://github.com/rohit123466/bus-tracking/pull/19), `VERIFICATION.md`, `LIVE_TRANSIT.md`, source, tests, API contracts and deployment files.

- Exact public-number lookup independent of internal numeric database IDs; UP/DOWN routes and suffix variants; deduplicated realtime snapshots and nonoverlapping polling.
- Full-feed static index, configurable live-index archive, GTFS shapes, absolute TripUpdates, conservative geometry ETA and direct/one-transfer comparison.
- Automatic homepage GPS watcher, accuracy circle, recenter, stop autocomplete, ORS walking and labelled straight-line fallback.
- Vertical seat layout, wheelchair bays, passenger categories, segment-based ticketing, conductor dashboard, simulated camera badges and JWT-protected operator APIs.
- Java 21/Spring Boot 4.1, Maven wrapper, Vite/React/Leaflet, H2 demo profile and Render Blueprint.

## Completed and fixed during this task

| Area | Status | Change and evidence |
|---|---|---|
| Exact public routes | COMPLETE | Existing exact matching retained. Conflicting trip direction now fails closed in live results. Tests cover 534/variants and both directions; actual bundled-data autocomplete returns 534, 534A, 534C, 534E separately. |
| GPS and freshness | COMPLETE | Undated fixes no longer inherit feed publication time. Invalid/future coordinates excluded. Explicit zero speed remains stopped; absent-speed inference requires recent compatible observations. Browser markers/arrivals expire independently of network polling. |
| Realtime identity | COMPLETE | Predictions match service date/start time, vehicle, route, direction, stop ID and stop sequence. Cancellations expire and are scoped to service instances. Unsupported trip relationships fail closed. Fixture regressions passed. |
| Legacy live APIs | COMPLETE | Publication uses validated route mapping; stale cache rows are excluded, Redis writes have a TTL, and old fixed-speed nearby ETA was removed. No numeric ETA is substituted for missing route evidence. |
| Stop arrival display | PARTIAL | Adds direction/current coordinates, kilometre route distance, source/confidence and GPS time. Algorithm fixtures verify progression and missing-data behavior; real arrival accuracy is blocked by feeds. |
| GTFS replacement | COMPLETE | Live index rejects orphan trip mappings, invalid stops and invalid shapes; ordered replacement shapes and optional missing stop times tested. Legacy importer supports a separate configurable archive and named CSV columns, preserving existing DB IDs. |
| Import recovery | COMPLETE | New initial relational imports are transactional; batches flush/clear ORM state. A database integration test fails after a flushed 1,000-row batch, confirms rollback, retries 1,001 rows successfully and verifies no reimport duplication. |
| Walking navigation | PARTIAL | Existing GPS/rerouting behavior retained. Invalid/non-numeric provider metrics or coordinates cannot become valid zero-minute walks. Mock provider/browser tests and actual unavailable-provider fallback passed; real ORS/on-street routing blocked. |
| Alternatives | PARTIAL | Existing wait/ride/walk/transfer timing retained; responses add stop names and walking distance, UI adds time saved. Direct and one-transfer fixture tests pass; no actual faster journey is claimed. Comparison starts at the selected boarding stop. |
| Accessibility/ticketing | COMPLETE | Vertical layout preserved and visually inspected at mobile width. Wheelchair category, seat selection, conductor login, sale, duplicate rejection, cancellation and camera API checked against actual backend demo data. Seat/occupancy UI explicitly identifies demo data. |
| Production configuration | PARTIAL | Production requires explicit JWT/admin secrets; public actuator exposure reduced. Blueprint uses readiness health and passes official schema validation. No target production deployment or connectivity verification. |
| Cleanup | COMPLETE | Removed unreferenced protobuf snapshots and unused React/Vite logos. No credential, database, build output or runtime log committed. |

## Executed tests

### Maven

- Java: locally installed Temurin **21.0.12.1**; default machine Java 24 was not used for verification.
- Spring Boot **4.1.0**, wrapper Maven **3.9.16** retained; no downgrade.
- `mvnw.cmd clean verify`: **51 tests, 0 failures, 0 errors, 0 skipped**, executable JAR built. Includes H2 application context, live route/ETA/alternatives, walking-provider, feed errors, freshness, accessibility seat rules and transactional import rollback/retry.
- Final checkout `mvnw.cmd clean verify` passed again: **51 tests, 0 failures/errors/skips**, BUILD SUCCESS on October 13. The backend was stopped first to avoid Windows file locks.
- Existing Redis Jackson 2 serializer deprecation and JVM test-agent warnings remain; neither failed compilation/tests. Production Redis connectivity was not tested.

### Frontend

- `npm ci`: passed; audit reported **0 vulnerabilities**.
- `npm test`: **6 passed**.
- `npm run lint`: passed.
- `npm run build`: passed, 227 modules transformed.
- `npm run test:browser`: **11 mocked API/GPS scenarios passed**, plus autocomplete mouse/keyboard, both stop fields, no matches, errors and Escape checks.
- `npm run test:runtime`: **passed against actual Spring Boot + Vite**, with simulated browser GPS and demo ticket data. Tests health/status codes, public routes, unavailable feeds, walking fallback, allowed/rejected CORS origins, STOMP CONNECT, mobile vertical seats, disability category, conductor login, admin access, ticket sale, duplicate HTTP 409/original ticket reference, cancellation and camera API. No browser `pageerror` events.
- The new runtime harness initially assumed a wheelchair category name and successful duplicate replay; corrected to the existing `DIVYANGJAN` category and documented duplicate HTTP 409 contract. These were test corrections, not ticketing API regressions.

## Actual application and restart checks

`mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"` and `npm run dev -- --port 5173 --strictPort` were run. Initial bundled import completed with **2,181,503 stop times**, **5,578 linked stops**, **76,890 trips** and **2,093 regional routes**. Three demo ticketing buses were then seeded.

The final packaged backend was started against that database, skipped the completed import, returned health UP and passed the runtime browser workflow. Packaged startup took approximately 52 seconds on this machine. The full initial import ran before the final transactional importer change; the new rollback/clear behavior was verified with the database integration fixture, not another full-scale initial load. During the full import the JVM working set exceeded 1 GB, so the Blueprint's free 512 MB plan is not a validated production capacity.

## Real DTC and external data results

**BLOCKED — authenticated live vehicles, real stop ETA and real alternative comparison.** No `DTC_API_KEY`, `DTC_TRIP_UPDATES_URL` or `ORS_API_KEY` was available in this chat's runtime environment. No historical credential was extracted from other chats/files.

- Actual official VehiclePositions request without a key returned **HTTP 401**. Actual local API reports an explicit missing-key warning and zero vehicles. Route 534's empty result in this task is not evidence that no 534 buses were operating.
- Direct inspection of bundled ZIP: no `shapes.txt`; maximum calendar end date **2025-01-01**.
- [Delhi official documentation](https://otd.delhi.gov.in/documentation/) publishes the authenticated VehiclePositions endpoint and warns that static stop times are approximate. [Official static-data page](https://otd.delhi.gov.in/data/static/) still displayed June 20, 2024 as its update date when checked. No current compatible feed was established or invented.
- GTFS validation follows the [GTFS Realtime reference](https://gtfs.org/documentation/realtime/reference/). Conflicting/unknown mappings are excluded with warnings; this intentionally reduces coverage rather than assigning uncertain buses.
- Supply a current compatible official archive via `GTFS_RESOURCE`, authorized `DTC_API_KEY`, an actual supported `DTC_TRIP_UPDATES_URL` if available, and `ORS_API_KEY`. Restart and verify route IDs/trip IDs, fresh GPS, both directions, physical route progression and provider coverage before claiming real-world accuracy.
- Delay-only TripUpdates, differential feeds and unresolved frequency/service instances remain unavailable. Expired schedules are not used for new live arrival predictions. The separately preserved legacy journey planner remains heuristic and is not used by reliable live alternatives.

## Production deployment status

**BLOCKED — no deployment of this repository was found or performed.** User authorized read-only inspection of Render “My Workspace.” Its listed service named “BusTracking” points to `ACHYUTKRCHAUDHARY/BusTracking`, not `rohit123466/bus-tracking`; its historical live deployment does not verify this branch. No matching target PostgreSQL/Redis resources were found in the workspace inventory. Unrelated services were not changed.

- COMPLETE: Blueprint JSON schema validation using Render's official schema; configuration/code review; local health, CORS, WebSocket and restart checks.
- BLOCKED: actual production PostgreSQL/Redis connectivity, secret configuration, GTFS import capacity, deployed frontend/API/WebSocket integration and sustained performance.
- PARTIAL: local responsiveness verified; no production load test or high-availability certification. Existing pre-fix partially imported databases need a reviewed migration/recovery; this change does not overwrite them.

## Windows instructions

From the repository root, with JDK 21, Node 22.12+ or 24, Git and Git LFS installed:

```powershell
git lfs pull
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd BusTracking\backend
.\mvnw.cmd -version
# Stop any running backend before clean (Windows locks the copied GTFS ZIP).
.\mvnw.cmd clean verify
# Optional live credentials: use runtime environment variables, never source control.
# $env:DTC_API_KEY, $env:DTC_TRIP_UPDATES_URL, $env:ORS_API_KEY
# $env:GTFS_RESOURCE = 'file:C:/data/compatible-GTFS.zip'
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Wait until initial import and demo seeding finish. In a second PowerShell window:

```powershell
cd BusTracking\frontend
npm ci
npm test
npm run lint
npm run build
npx playwright install chromium
npm run test:browser
npm run dev -- --port 5173 --strictPort
```

With actual demo backend/frontend running, use a third window to run `npm run test:runtime` from the frontend directory. It creates and cancels a demo ticket; **never point that harness at production**. Defaults match the local demo admin account; set `TEST_ADMIN_USERNAME`/`TEST_ADMIN_PASSWORD` if changed. Geolocation requires localhost/HTTPS and browser permission. Production requires the environment settings described in `DEPLOY.md` and its own verified capacity.

## Pull request

PR URL: https://github.com/rohit123466/bus-tracking/pull/23 . Base `main`; feature branch only. No force push or merge.

## Files modified

See the exact list appended below (paths relative to repository root).
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/AlternativeService.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/LiveTransitService.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/RealtimeSnapshot.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/TransitIndex.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/WalkingService.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/BusLocationIngestService.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/DtcApiClient.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/GtfsImportService.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/InMemoryBusLocationStore.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/NearbyBusService.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/RedisBusLocationStore.java
- BusTracking/backend/src/main/resources/application-prod.properties
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/LiveTransitTest.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/WalkingServiceTest.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/service/DtcApiClientTest.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/service/GtfsImportRecoveryTest.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/service/LegacyFreshnessTest.java
- BusTracking/backend/test.pb
- BusTracking/DEPLOY.md
- BusTracking/DEVELOPMENT_CHECKLIST.md
- BusTracking/FINAL_VERIFICATION.md
- BusTracking/frontend/package.json
- BusTracking/frontend/src/assets/react.svg
- BusTracking/frontend/src/assets/vite.svg
- BusTracking/frontend/src/components/BusMap.jsx
- BusTracking/frontend/src/components/OccupancyDashboard.jsx
- BusTracking/frontend/src/pages/HomePage.jsx
- BusTracking/frontend/src/pages/SeatAvailabilityPage.jsx
- BusTracking/frontend/src/utils/navigation.js
- BusTracking/frontend/tests/navigation.test.js
- BusTracking/frontend/tests/real-backend.mjs
- BusTracking/frontend/tests/runtime.mjs
- BusTracking/LIVE_TRANSIT.md
- feed_check.pb
- render.yaml

### Walking navigation follow-up
- COMPLETE: delayed GPS automatically resumes nearest-stop lookup; approximate GPS permits provisional stop discovery without turn instructions; retry remains available; timed retries no longer depend on GPS movement.
- COMPLETE: unavailable pedestrian routing shows a labelled dashed straight-line connection; no-stop results no longer display a fabricated zero-metre distance.
- Verification: frontend lint/build passed; 11 existing mock browser scenarios passed; tests/walking-fallback.mjs passed against the running backend with simulated delayed/coarse GPS.
- BLOCKED: street-level pedestrian directions require ORS_API_KEY. The supplied DTC credential is active only in the running backend environment and was not changed or committed.


### Nearby vehicle follow-up
- COMPLETE: homepage defaults to buses within 2 km of browser location, with a 500 m–10 km selector and an all-bus toggle. Distances measure user-to-vehicle proximity and sort nearest first; movement updates results. Stale user fixes and stale vehicle positions are excluded. GPS accuracy text is hidden at the user's request; internal safety checks remain.
- COMPLETE: 7 frontend unit tests, lint and build passed; 11 existing mock browser scenarios and the new nearby-buses.mjs moving-GPS/radius scenario passed.
- PARTIAL: authenticated live API returned 319 verified mapped vehicles at the follow-up check; 1063 mapping conflicts and 406 stale/undated vehicles were excluded. Counts fluctuate. Browser proximity verification uses simulated GPS and vehicles, not the user's physical position. The earlier missing-DTC-key result above is historical and superseded by successful authenticated ingestion; ORS and compatible static-data limitations remain.
- PR: https://github.com/rohit123466/bus-tracking/pull/23

### Freshness and actual browser validation
- COMPLETE: inspected the user browser and confirmed a real nearby vehicle with a recent report. A subsequent independent live API/filter check also returned a nearby vehicle, and later browser observation showed its distance changing. No user coordinates are recorded in this report.
- COMPLETE: periodically request a fresh browser location every 15 seconds because stationary devices may not emit watch events. Requests do not overlap, timestamps are never fabricated, and timer/watch cleanup occurs on unmount.
- COMPLETE: replace ambiguous active-bus wording with recently reported buses and show per-vehicle GPS report age. Keep the existing 120-second vehicle cutoff, exact-route matching and coordinate validation. Freshness is not proof of passenger service.
- Validation: 7 unit tests, lint, build, 11 mock browser scenarios and nearby radius/movement browser checks passed. Authenticated data still has substantial static mapping gaps, explicitly disclosed to the user; no uncertain routes were assigned.
