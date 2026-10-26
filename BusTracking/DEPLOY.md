# Deploying to Render + Neon

The API, frontend and Redis run on Render; the database is [Neon](https://neon.tech) serverless Postgres.
`render.yaml` (at the repo root) defines the Render side:

| Resource | Type | Notes |
|---|---|---|
| `bus-tracker-api` | Docker web service | Spring Boot, `prod` profile |
| `bus-tracker-web` | Static site | React/Vite build, SPA rewrite |
| `bus-tracker-redis` | Key Value (Redis) | live bus positions |
| Neon project (outside Render) | Postgres | GTFS data + bus history |

## Steps

1. **Create the Neon database.** At [console.neon.tech](https://console.neon.tech) create a project (pick the region closest to your Render region, e.g. AWS Singapore for Render Singapore). Name the database `dtc_bus_tracker` (or keep `neondb`).
2. In Neon, click **Connect**, turn **Connection pooling off** (use the direct host, `ep-xxx.<region>.aws.neon.tech`, not `ep-xxx-pooler...`; the app's Hikari pool already pools connections), and choose **Parameters only**. Keep the `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` values for step 5.
3. Push this repo to GitHub, including `render.yaml`.
4. Render dashboard -> **New -> Blueprint** -> select the repo -> **Apply**.
5. Render asks for the values marked `sync: false`. Enter:
   - `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` - from Neon (step 2)
   - `DTC_API_KEY` - your Delhi OTD API key
   - `CORS_ALLOWED_ORIGINS` - `https://bus-tracker-web.onrender.com` (the frontend's exact URL, no trailing slash)
   - `VITE_API_URL` - `https://bus-tracker-api.onrender.com/api`

   If Render appends a suffix to a service name (name already taken), use the real URLs shown on each service page, then edit these variables and redeploy.
6. First boot of the API imports GTFS into Neon (a few minutes; watch the logs for "GTFS already seeded" on later boots).
7. Admin login: username `admin`, password is the generated `ADMIN_PASSWORD` (Environment tab of the API service).

## Things to know

- **Startup safety check:** the `prod` profile refuses to start if `JWT_SECRET` is shorter than 32 bytes, `ADMIN_PASSWORD` is weak (shorter than 12 characters, or a default like `admin123`), or `CORS_ALLOWED_ORIGINS` is empty or a wildcard. The values `render.yaml` generates pass. If a deploy fails at boot, the log line starting `Refusing to start in prod:` names the variable to fix.
- **Learned routes:** `RouteLearner` saves to `/app/data/learned-routes.txt`. On the free plan that disk is wiped on every deploy and restart. Add a Render persistent disk (paid plans) mounted at `/app/data` to keep it.
- **Neon TLS:** Neon only accepts encrypted connections, so the `prod` profile connects with `sslmode=require` by default. Set `PGSSLMODE=disable` only for a local Postgres without TLS (`docker-compose.yml` does this). `PGPORT` defaults to `5432`.
- **Neon scale to zero:** the free Neon compute suspends after ~5 min with no queries and wakes on the next connection (a sub-second delay). The data is kept. Hikari discards dead connections automatically, so nothing needs restarting.
- **History retention:** bus position history older than `BUS_HISTORY_RETENTION_HOURS` (default 24) is purged hourly, which keeps the free Neon storage (0.5 GB per project) from filling up. Lower it if the Neon dashboard shows storage near the limit.

- **GTFS.zip is stored in Git LFS.** The Docker build fails early if it only gets the LFS pointer. If Render doesn't fetch LFS for your repo, remove the LFS rule (`BusTracking/.gitattributes`) and re-commit the zip as a normal file (55 MB, under GitHub's 100 MB limit).
- **Import area:** `GTFS_IMPORT_RADIUS_DEGREES=0.16` limits data to North/North West Delhi so it fits a 512 MB instance and the free Neon storage. Set it to `0` for the full Delhi feed - use larger plans, and wipe the DB first (or create a fresh Neon branch), since the import is skipped once routes exist. The live-bus filter uses the same route set.
- **Free plans:** everything in `render.yaml` uses Render's free tier, and Neon has a free plan (no card needed for either).
  - The API sleeps after ~15 min without traffic; the first request afterwards takes ~1 min to wake it, and live-bus polling only runs while it is awake.
  - Neon's free plan does not expire (unlike Render's free Postgres, which is deleted after 30 days).
  - For always-on, change the API plan to `starter`; upgrade the Neon plan for more storage or compute.
- The `demo` profile (H2) is for local use only; Render runs `prod`.
