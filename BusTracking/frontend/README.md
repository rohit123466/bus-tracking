# DTC Bus Tracker — Frontend

React + Vite + Leaflet passenger app for Delhi buses: a live map of buses on North and North-West
Delhi routes, arrivals at nearby stops, a timetable journey planner, and saved routes/stops with
arrival alerts. Light and dark themes; works on phones (bottom tab bar, map bottom sheet).

## Run

```bash
npm install
npm run dev          # http://localhost:5173, API at http://localhost:8080/api (see ../backend)
npm run build        # production build into dist/
npm test             # unit tests (node --test)
npm run test:browser # browser checks with fixture responses; uses installed Edge (PW_CHANNEL=chromium to change)
npm run lint
```

`VITE_API_URL` (build time) points the app at a deployed backend, e.g.
`https://bus-tracking-bl5x.onrender.com/api`. `vercel.json` rewrites every path to `index.html`
so deep links such as `/nearby` work.

## Pages

| Path | What it shows | Backend |
| --- | --- | --- |
| `/` Explore | Live buses (10 s polling), route search, route stops and direction lines, bus and stop details. Selection is in the URL (`?route=&bus=&stop=`). | `/transit/buses/regional`, `/transit/routes/patterns` (falls back to `/transit/routes/map`), `/transit/stops/nearby-regional` |
| `/nearby` | Stops around you with next buses and how each time was estimated | `/transit/stops/nearby-regional`, `/transit/stops/nearby` outside the covered area |
| `/journey` | Direct and one-change journeys with a step timeline and map | `/transit/journey/plan` |
| `/saved` | Favourite routes/stops, arrival alerts, home/work, recent searches | browser storage |

The older tools (vehicle lookup, replay, classic route view, seat and conductor demos, admin) are
under the ☰ menu.

## Structure

- `src/api/client.js` — every backend call; timeouts, de-duplication of identical in-flight GETs,
  connection reporting. `errors.js` classifies failures; `connection.js` tracks waking / offline.
- `src/hooks/usePolling.js` — polling with abort on change/unmount, pause in hidden tabs, backoff.
- `src/hooks/useUserLocation.js` — one shared GPS watch; never prompts until the user asks.
- `src/store/` — localStorage-backed stores shared across pages and tabs (favourites, alerts, theme).
- `src/components/map/` — base map and layers (canvas dots when zoomed out, labelled pins when in).
- `src/components/ArrivalWatcher.jsx` — app-wide arrival alerts, each bus announced once.
- `src/styles/` — design tokens live in `src/index.css`; components and page layouts here.

## Data honesty rules

- A bus position older than 2 minutes is never drawn; 30 s–2 min is shown as "Delayed GPS".
- Only a computed ETA is shown as minutes, prefixed "≈" and labelled when it is an estimate.
- Journey times come from the timetable (or say they don't); waiting time is never included.
- The passenger pages make no wheelchair or accessibility claims: the live feed and timetable
  index have no verified data for them.

See [BACKEND_NOTES.md](BACKEND_NOTES.md) for the original API notes.
