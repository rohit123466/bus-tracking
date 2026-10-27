# DTC Bus Tracker

Live bus tracking for Delhi Transport Corporation buses, focused on North and North West Delhi. It shows buses on a map, arrival times at nearby bus stands, route search, journey planning, walking directions, and a demo seat-ticketing/conductor console.

[![CI](https://github.com/rohit123466/bus-tracking/actions/workflows/ci.yml/badge.svg)](https://github.com/rohit123466/bus-tracking/actions/workflows/ci.yml)

## Architecture

```
Browser (React + Vite + Leaflet)
   │  REST /api/*        STOMP over SockJS /ws (live positions)
   ▼
Spring Boot 4 API (Java 21) ──► Delhi OTD realtime feed (GTFS-RT VehiclePositions, polled every 4 s with ETags; it changes every ~11 s)
   │        │
   │        └─ in-memory GTFS index (static/GTFS.zip) + RouteLearner (learns real routes from GPS)
   ▼
PostgreSQL (stops, routes, trips, tickets, 24 h bus history)    Redis (latest position per bus)
```

| Path | What it is |
|---|---|
| `BusTracking/backend` | Spring Boot API: live transit (`live/`), legacy REST (`controller/`), ticketing demo, JWT admin auth |
| `BusTracking/frontend` | React 19 single-page app |
| `render.yaml` | Render Blueprint (production hosting) |
| `docker-compose.yml` | The whole stack on one machine |

### Data caveat

The bundled static GTFS is from June 2024 and no newer one is published. Most live buses no longer drive the route their feed ID points to, so the backend **learns real routes from live GPS** (`RouteLearner`, saved to `data/learned-routes.txt`). Arrival times for a route appear after about 1–2 hours of learning. See [LIVE_TRANSIT.md](BusTracking/LIVE_TRANSIT.md).

## Running locally

### Option A: no Docker (H2 database, `demo` profile)

Needs Java 21 and Node 22+.

```bash
# Terminal 1 - API on http://localhost:8080
cd BusTracking/backend
export DTC_API_KEY=your-key          # PowerShell: $env:DTC_API_KEY="your-key"
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo

# Terminal 2 - app on http://localhost:5173
cd BusTracking/frontend
npm install
npm run dev
```

The first boot imports the GTFS feed into `BusTracking/backend/data/` (a few minutes). Without `DTC_API_KEY` the app runs, but shows no live buses.

### Option B: full stack in Docker (Postgres, Redis, `prod` profile)

```bash
git lfs pull                 # GTFS.zip is stored in Git LFS
cp .env.example .env         # fill in JWT_SECRET, ADMIN_PASSWORD, DTC_API_KEY
docker compose up --build
```

App: http://localhost:8081 · API: http://localhost:8080/api · Health: http://localhost:8080/actuator/health

## Configuration

Set as environment variables. The `prod` profile **refuses to start** if the secrets are missing or weak.

| Variable | Required in prod | Default (dev) | Purpose |
|---|---|---|---|
| `DTC_API_KEY` | yes | — | Delhi Open Transit Data key; no key, no live buses |
| `JWT_SECRET` | yes, 32+ bytes | dev-only value | Signs admin login tokens |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | password yes, 12+ chars | `admin` / `admin123` | Seeded admin account (first boot only) |
| `CORS_ALLOWED_ORIGINS` | yes, no wildcards | `localhost:3000,5173` | Frontend origin(s) |
| `PGHOST` `PGPORT` `PGDATABASE` `PGUSER` `PGPASSWORD` | yes | local Postgres | Database |
| `REDIS_URL` | yes | `localhost:6379` | Live position store |
| `GTFS_IMPORT_RADIUS_DEGREES` | no | `0.16` (prod: `0` = all Delhi) | Area of the GTFS import |
| `BUS_HISTORY_RETENTION_HOURS` | no | `24` | Journey Replay history kept; `0` keeps everything |
| `LEARNED_ROUTES_FILE` | no | `./data/learned-routes.txt` | Where learned routes are saved |
| `API_DOCS_ENABLED` | no | on in dev, off in prod | Swagger UI at `/swagger-ui.html` |
| `ORS_API_KEY` | no | — | OpenRouteService walking directions |
| `VITE_API_URL` (frontend build) | yes for a separately hosted API | `http://localhost:8080/api` | API base URL |

Spring profiles: `demo` (H2, no infrastructure), `local`/`dev` (local Postgres + Redis), `prod` (hosted).

## Testing

```bash
cd BusTracking/backend && ./mvnw verify       # JUnit (unit + Spring context)
cd BusTracking/frontend && npm run lint && npm test && npm run build
```

Every push and pull request runs the same checks in GitHub Actions ([ci.yml](.github/workflows/ci.yml)), plus CodeQL security scanning. Dependabot opens weekly dependency updates.

> **Note:** if the backend is running under `spring-boot:run`, stop it before `./mvnw verify`. DevTools reloads while Maven rebuilds `target/classes`, which kills the running app.

## Operations

- **Health:** `GET /actuator/health` (with `/liveness` and `/readiness` groups). Only `health` and `info` are exposed in prod.
- **Learning status:** `GET /api/transit/learning/status`: how many routes have been learned so far.
- **Security:** rider endpoints are public; `/api/admin/**` and `/api/conductor/**` need an admin JWT. After 5 failed logins from one address, that address is locked out for 15 minutes. The API runs as a non-root user in its container.
- **Data growth:** bus position history is purged hourly beyond `BUS_HISTORY_RETENTION_HOURS`.
- **Learned routes on Render:** the free plan's disk is wiped on every deploy and restart, so learning starts over. Attach a Render persistent disk at `/app/data` to keep it.

## Deployment

Render, from [render.yaml](render.yaml): see [BusTracking/DEPLOY.md](BusTracking/DEPLOY.md).

## More documentation

- [LIVE_TRANSIT.md](BusTracking/LIVE_TRANSIT.md): live transit APIs, route matching, arrival prediction, data limits
- [TICKETING.md](BusTracking/TICKETING.md): seat map and segment ticketing (demo)
- [VERIFICATION.md](BusTracking/VERIFICATION.md): verification log
- [Frontend README](BusTracking/frontend/README.md)
