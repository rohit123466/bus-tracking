# DTC live tracking verification (2026-10-12)

## Changes

- Windows build helper validates Java 21 and reads Java stderr safely under Windows PowerShell 5.1 (validate goal verified).
- API errors preserve their HTTP status: invalid coordinates return 400; unexpected failures return 500 without exposing internal details.
- Homepage owns one GPS watcher, requests permission on load, and keeps the blue marker/accuracy circle current independently of walking navigation. Unmount clears the watcher. Added Center on My Location.
- Walking navigation reuses the page location and cancels its requests without cancelling GPS.
- DTC feed failures appear in live-bus and arrival responses. HTTP authorization failures and malformed protobuf are explicit; exceptions cannot expose the API key in request URLs.
- Legacy publication now uses the validated, deduplicated snapshot and excludes stale coordinates.
- Upgraded MobilityData GTFS bindings from 0.0.8 to 0.2.0, compatible with the existing protobuf 4.34.2 runtime. The older generated classes emitted a vulnerable-pre-22-gencode warning during actual feed parsing.
- Retained Java 21, Spring Boot 4.1.0, Springdoc 3.1.1, compiler annotation processing and all tests. No dependency downgrade or security bypass.
- Updated compatible frontend dependencies; npm audit reports zero vulnerabilities. Added reproducible Playwright dependency and browser command.
- Added original journey time to alternative comparison. Existing exact public-route filtering, trustworthy ETA, direct/one-transfer alternatives, routing fallback and ticketing features remain in place.
- Removed tracked runtime logs/PID files and added root ignore rules.

## Root causes and environment findings

The baseline already compiled and passed 30 backend tests. Maven dependency resolution, annotation processing, PostgreSQL driver resolution and Springdoc were not failing in this checkout. The machine default was Java 24; verification explicitly used a locally installed Temurin Java 21.0.12.1 and Maven wrapper 3.9.16.

Git's Windows Schannel backend initially failed with SEC_E_NO_CREDENTIALS. Using Git's OpenSSL backend enabled the clone; TLS verification remained enabled. The bundled GTFS archive requires Git LFS.

Running Maven clean while the backend was open caused a Windows file-lock error on target/classes/static/GTFS.zip. Stop the backend before clean. The clean build succeeded after stopping it.

## Verification

- Java 21 Maven clean verify through scripts/build-windows.ps1: 39 tests passed, zero failures/errors/skips, including the GTFS binding upgrade and API error regressions.
- Backend coverage includes both directions of 534, one-transfer connection feasibility, malformed/missing/stale feeds, ticketing/seat availability, and HTTP error status preservation.
- Maven dependency:tree, test and verify completed successfully; duplicate lifecycle test execution is coalesced by Surefire, not disabled.
- npm ci, npm test (4 tests), npm run lint, npm run build passed; npm audit: zero vulnerabilities.
- npm run test:browser: 11 mocked API/GPS browser scenarios passed. These cover automatic location, permission denial, actual marker movement, exact route filtering and polling, alternatives UI, walking progress, stopping navigation while retaining GPS, routing failure, mobile map visibility, and watcher cleanup on page navigation.
- tests/real-backend.mjs passed against actual Spring Boot/Vite and real DTC feed with simulated browser coordinates: GPS display, route 534 request, walking fallback and no browser runtime errors.
- Real key authenticated successfully: one verification snapshot returned 891 mapped live vehicles. Thousands of unmapped/conflicting vehicles and hundreds of stale positions were excluded. Counts naturally change.
- Route 534 returned zero live matches during the check. Autocomplete returned 534, 534A, 534C, 534E as separate choices; the exact filter does not combine them.
- Swagger UI and /v3/api-docs returned HTTP 200. CORS returned http://localhost:5173 for that origin.

## External data limitations

The bundled GTFS has no shapes.txt. DTC vehicle route/trip identifiers frequently do not match this static archive. A current matching static GTFS archive is required for complete live coverage. The code deliberately excludes conflicting mappings.

No DTC_TRIP_UPDATES_URL or ORS_API_KEY was supplied. Real along-route distance/ETA and fully timed alternatives cannot be demonstrated without adequate shapes/speed or fresh TripUpdates; real pedestrian turn-by-turn routing cannot be demonstrated without the routing provider key. Automated fixtures verify these algorithms but do not prove live production predictions. The Delhi provider itself states static stop times are rough estimates, not reliable actual arrivals: https://otd.delhi.gov.in/documentation/ .

The key is runtime-only and is not stored in this repository. Production PostgreSQL/Redis were not provisioned; demo verification uses H2 and in-memory location storage.

## Windows startup

Use two PowerShell windows, from the repository root. Set JAVA_HOME to your JDK 21 installation. The verified initial import completed with 2,181,503 stop times and 5,578 linked stops, and health returned HTTP 200. Initial GTFS database import can take several minutes; wait for readiness to become UP before using legacy timetable features. Do not interrupt initial import.

Backend:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$secret = Read-Host 'DTC API key' -AsSecureString
$env:DTC_API_KEY = [System.Net.NetworkCredential]::new('', $secret).Password
# Optional: $env:ORS_API_KEY and $env:DTC_TRIP_UPDATES_URL
cd BusTracking\backend
java -version
.\mvnw.cmd -version
.\scripts\build-windows.ps1 -Goals clean,verify
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

Frontend:

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

URLs: http://localhost:5173, http://localhost:8080, http://localhost:8080/swagger-ui.html. Browser location permission must be granted; physical GPS movement is not validated by browser simulation.

## Modified files

- .gitignore
- BusTracking/backend/scripts/build-windows.ps1
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/exception/GlobalExceptionHandler.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/TransitErrorTest.java
- BusTracking/backend/pom.xml
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/live/{LiveTransitService,RealtimeSnapshot}.java
- BusTracking/backend/src/main/java/com/dtc/bus_tracker/service/DtcApiClient.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/live/LiveTransitTest.java
- BusTracking/backend/src/test/java/com/dtc/bus_tracker/service/DtcApiClientTest.java
- BusTracking/frontend/package.json and package-lock.json
- BusTracking/frontend/src/components/BusMap.jsx
- BusTracking/frontend/src/hooks/{useUserLocation,useWalkingNavigation}.js
- BusTracking/frontend/src/pages/HomePage.jsx
- BusTracking/frontend/tests/{browser,real-backend}.mjs
- This verification report; tracked runtime logs and PID files removed.

Final packaged-JAR verification on October 12: health and Swagger HTTP 200; invalid coordinates HTTP 400; unknown stop HTTP 404; unauthenticated admin HTTP 403. A fresh real-feed snapshot contained 563 mapped live vehicles. Backend started in approximately 40 seconds using the completed H2 import. The earlier 891-vehicle snapshot was observed on October 11.
