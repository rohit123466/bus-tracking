# DTC Bus Tracker — Frontend

React + Vite app. Leaflet map centered on Delhi, live nearby-bus markers,
route search, and route path display.

## Run

```bash
npm install
npm run dev
```

Requires the backend running at `http://localhost:8080` (see
`../backend`). CORS is already configured there for `localhost:5173`.

## Structure

- `src/api/client.js` — API calls to the Spring Boot backend
- `src/components/BusMap.jsx` — the Leaflet map (buses, user location, route path/stops)
- `src/components/SearchBar.jsx` — route search with autocomplete
- `src/utils/leafletIcons.js` — marker icon setup (default icon fix + custom bus/user/stop icons)
- `src/App.jsx` — geolocation tracking, 10s bus polling, route selection state

See [BACKEND_NOTES.md](BACKEND_NOTES.md) for the original requirements doc
and notes on backend API quirks (param names, missing endpoints, etc.).
