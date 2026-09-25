# Deploying to Render

`render.yaml` (at the repo root) defines everything:

| Resource | Type | Notes |
|---|---|---|
| `bus-tracker-api` | Docker web service | Spring Boot, `prod` profile |
| `bus-tracker-web` | Static site | React/Vite build, SPA rewrite |
| `bus-tracker-db` | Postgres | GTFS data + bus history |
| `bus-tracker-redis` | Key Value (Redis) | live bus positions |

## Steps

1. Push this repo to GitHub, including `render.yaml`.
2. Render dashboard -> **New -> Blueprint** -> select the repo -> **Apply**.
3. Render asks for the values marked `sync: false`. Enter:
   - `DTC_API_KEY` - your Delhi OTD API key
   - `CORS_ALLOWED_ORIGINS` - `https://bus-tracker-web.onrender.com` (the frontend's exact URL, no trailing slash)
   - `VITE_API_URL` - `https://bus-tracker-api.onrender.com/api`

   If Render appends a suffix to a service name (name already taken), use the real URLs shown on each service page, then edit these variables and redeploy.
4. First boot of the API imports GTFS into Postgres (a few minutes; watch the logs for "GTFS already seeded" on later boots).
5. Admin login: username `admin`, password is the generated `ADMIN_PASSWORD` (Environment tab of the API service).

## Things to know

- **GTFS.zip is stored in Git LFS.** The Docker build fails early if it only gets the LFS pointer. If Render doesn't fetch LFS for your repo, remove the LFS rule (`BusTracking/.gitattributes`) and re-commit the zip as a normal file (55 MB, under GitHub's 100 MB limit).
- **Import area:** `GTFS_IMPORT_RADIUS_DEGREES=0.16` limits data to North/North West Delhi so it fits a 512 MB instance. Set it to `0` for the full Delhi feed - use a larger plan, and wipe the DB first, since the import is skipped once routes exist. The live-bus filter uses the same route set.
- **Free plans:** everything in `render.yaml` uses Render's free tier (no card needed).
  - The API sleeps after ~15 min without traffic; the first request afterwards takes ~1 min to wake it, and live-bus polling only runs while it is awake.
  - Free Postgres is deleted after 30 days (create a new one, or upgrade, to keep the data). It holds 1 GB.
  - For always-on, change the API plan to `starter` and the database to `basic-256mb`.
- The `demo` profile (H2) is for local use only; Render runs `prod`.
