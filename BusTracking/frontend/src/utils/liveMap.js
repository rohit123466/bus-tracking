// Live Map helpers: region filter options, GPS freshness and ETA labels.
// Pure functions so they can be unit-tested with `node --test`.

// Every option hits /api/transit/buses/regional, which only returns buses inside the area.
export const REGION_OPTIONS = [
  { value: 'ANY', label: 'North or North-West Delhi routes' },
  { value: 'NORTH', label: 'North Delhi routes' },
  { value: 'NORTH_WEST', label: 'North-West Delhi routes' },
];

export const DEFAULT_REGION = 'ANY';

export const regionLabel = (value) =>
  REGION_OPTIONS.find(o => o.value === value)?.label ?? value;

export const isRegional = (region) => !!region && region !== 'ALL';

// Positions older than 120 s are already dropped by the backend and visibleBuses().
const LIVE_MAX_AGE_S = 30;

/** LIVE (<=30 s old), DELAYED (30-120 s) or STALE; never claims a position is newer than it is. */
export function busFreshness(bus, now = Date.now() / 1000) {
  const stamp = bus?.lastUpdatedEpochSeconds;
  if (!stamp) return 'STALE';
  const age = now - stamp;
  if (age <= LIVE_MAX_AGE_S) return 'LIVE';
  if (age <= 120) return 'DELAYED';
  return 'STALE';
}

export const FRESHNESS_TEXT = {
  LIVE: 'Live GPS',
  DELAYED: 'Delayed GPS',
  STALE: 'Location unavailable',
};

/** Badge text for an upcoming-bus entry; only a real number is ever shown as minutes. */
export function etaText(upcoming) {
  if (upcoming?.etaMinutes == null || !Number.isFinite(upcoming.etaMinutes)) return 'ETA unavailable';
  const m = Math.max(0, Math.round(upcoming.etaMinutes));
  const prefix = upcoming.availability === 'TRIP_UPDATE' ? '' : '≈ ';
  return m < 1 ? `${prefix}< 1 min` : `${prefix}${m} min`;
}

export const ETA_KIND = {
  TRIP_UPDATE: 'live',
  GEOMETRY_ESTIMATE: 'estimate',
  STOP_CHAIN_ESTIMATE: 'estimate',
  TYPICAL_SPEED_ESTIMATE: 'estimate',
  LEARNED_ROUTE_ESTIMATE: 'estimate',
  UNAVAILABLE: 'unavailable',
};

/** Popup note explaining where a bus's next-stop ETA came from. */
export const ETA_BASIS_TEXT = {
  LEARNED_ROUTE_ESTIMATE: 'based on how long a bus on this route recently took',
  STOP_CHAIN_ESTIMATE: 'rough estimate from current speed',
};

function metresBetween(a, b) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(h));
}

/** Same-name stops closer than this are one bus stand (usually one stop per side of the road). */
export const STAND_MERGE_METERS = 150;

/**
 * Merges GTFS stops into bus stands: same name and within STAND_MERGE_METERS. Each stand keeps its
 * nearest stop (for selecting / walking to it), the union of routes, and every arrival once.
 * Input is nearest-first, so stands are too.
 */
export function groupStands(stops) {
  const stands = [];
  for (const s of stops ?? []) {
    const name = (s.name ?? '').trim();
    const stand = stands.find(x => x.name.toLowerCase() === name.toLowerCase()
      && x.members.some(m => metresBetween(m, s) <= STAND_MERGE_METERS));
    if (!stand) {
      stands.push({ ...s, name, members: [s], routeNumbers: [...(s.routeNumbers ?? [])], upcoming: [...(s.upcoming ?? [])] });
      continue;
    }
    stand.members.push(s);
    stand.routeNumbers = [...new Set([...stand.routeNumbers, ...(s.routeNumbers ?? [])])];
    for (const u of s.upcoming ?? []) {
      const i = stand.upcoming.findIndex(x => x.vehicleId === u.vehicleId);
      if (i < 0) stand.upcoming.push(u);
      else if ((u.etaMinutes ?? Infinity) < (stand.upcoming[i].etaMinutes ?? Infinity)) stand.upcoming[i] = u;
    }
  }
  return stands.map(st => ({ ...st, liveCount: st.upcoming.length }));
}

const byRouteNumber = (a, b) => a.localeCompare(b, undefined, { numeric: true });

/**
 * Buses of one route standing at the same spot (same distance and ETA) are one entry with a
 * `count` — usually several buses waiting at a terminal or depot. Input is sorted by ETA.
 */
function collapseTogether(buses) {
  const out = [];
  for (const b of buses) {
    const prev = out[out.length - 1];
    const together = prev && b.distanceMeters != null && prev.distanceMeters != null
      && Math.abs(b.distanceMeters - prev.distanceMeters) <= 100
      && Math.abs((b.etaMinutes ?? Infinity) - (prev.etaMinutes ?? Infinity)) <= 1;
    if (together) prev.count += 1;
    else out.push({ ...b, count: 1 });
  }
  return out;
}

/**
 * A stand's arrivals grouped by route: routes with a tracked bus first (soonest first, each with
 * its buses soonest first), then the routes that stop here with no bus tracked right now.
 */
export function arrivalsByRoute(stand) {
  const groups = new Map();
  for (const u of stand?.upcoming ?? []) {
    if (!groups.has(u.routeNumber)) groups.set(u.routeNumber, []);
    groups.get(u.routeNumber).push(u);
  }
  const soonest = list => list[0]?.etaMinutes ?? Infinity;
  const tracked = [...groups.entries()]
    .map(([routeNumber, buses]) => ({
      routeNumber,
      buses: collapseTogether(buses.sort((a, b) => (a.etaMinutes ?? Infinity) - (b.etaMinutes ?? Infinity))),
    }))
    .sort((a, b) => soonest(a.buses) - soonest(b.buses) || byRouteNumber(a.routeNumber, b.routeNumber));
  const idle = (stand?.routeNumbers ?? []).filter(r => !groups.has(r)).sort(byRouteNumber);
  return { tracked, idle };
}

/** "Learned real paths for 120 of 450 routes running now", or null before the first status. */
export function learningText(status) {
  if (!status || !status.routesRunningNow) return null;
  return `Learned real paths for ${status.learnedOfRunning} of ${status.routesRunningNow} routes running now`;
}

export { distanceText } from './format.js';

/** How an ETA was produced, shown as text next to it so it never relies on colour alone. */
export const ETA_SOURCE_TEXT = {
  TRIP_UPDATE: 'Live prediction',
  GEOMETRY_ESTIMATE: 'Estimate from live speed',
  STOP_CHAIN_ESTIMATE: 'Estimate from live speed',
  TYPICAL_SPEED_ESTIMATE: 'Rough estimate at typical bus speed',
  LEARNED_ROUTE_ESTIMATE: 'Estimate from recent trips on this route',
  UNAVAILABLE: 'No reliable estimate',
};

/**
 * Whether a bus's GPS fix fits the route number shown for it (`routeCheck` from the backend).
 * Only OFF_ROUTE is treated as a warning; a backend that predates the field sends nothing, which
 * is shown as "not checked" rather than as confirmed.
 */
export const ROUTE_CHECK_TEXT = {
  TRIP_MATCH: 'Route confirmed by the timetable trip',
  ON_ROUTE: 'On this route’s timetable path',
  OFF_ROUTE: 'Route number unconfirmed',
  NO_STOPS: 'Route can’t be checked (no timetable stops)',
};

export const routeUnconfirmed = bus => bus?.routeCheck === 'OFF_ROUTE';

/** Longer explanation for a bus whose route number does not fit its position. */
export const OFF_ROUTE_EXPLANATION =
  'This bus is more than 500 m from every stop of the route the live feed gives it. It may be at a depot or on a diversion, or the feed’s route mapping may be out of date, so it may not be running this route.';

/** ETAs above this, or clearly negative, are treated as invalid rather than shown. */
export const MAX_ETA_MINUTES = 180;
/** Matches the backend: a GPS fix older than this is no longer a live position. */
export const GPS_STALE_AFTER_S = 120;

/**
 * Whether an upcoming-bus ETA may be shown as a number right now:
 * 'ok', 'none' (no estimate), 'invalid' (not a plausible number), or 'stale' (its GPS fix has aged
 * past 2 minutes, e.g. while refreshes are failing, so the countdown no longer means anything).
 */
export function etaStatus(upcoming, now = Date.now() / 1000) {
  const m = upcoming?.etaMinutes;
  if (m == null || !Number.isFinite(m)) return 'none';
  if (m < -1 || m > MAX_ETA_MINUTES) return 'invalid';
  const gps = upcoming.lastGpsUpdate;
  if (Number.isFinite(gps) && gps > 0 && now - gps > GPS_STALE_AFTER_S) return 'stale';
  return 'ok';
}

/**
 * Stands served by `route` (exact public number), each narrowed to that route's arrivals. With no
 * route, the stands are returned unchanged. Stop coordinates and serving routes come from the
 * timetable, so a stand that lists the route is kept even when no bus is tracked to it.
 */
export function filterStandsByRoute(stands, route) {
  const wanted = (route ?? '').trim().toUpperCase();
  if (!wanted) return stands ?? [];
  return (stands ?? [])
    .filter(s => (s.routeNumbers ?? []).some(r => String(r).toUpperCase() === wanted))
    .map(s => {
      const upcoming = (s.upcoming ?? []).filter(u => String(u.routeNumber ?? '').toUpperCase() === wanted);
      return { ...s, routeNumbers: [wanted], upcoming, liveCount: upcoming.length };
    });
}

/** Soonest first; entries without a numeric ETA last, then by route number. */
export function sortByEta(list) {
  return [...(list ?? [])].sort((a, b) =>
    (a.etaMinutes ?? Infinity) - (b.etaMinutes ?? Infinity)
    || String(a.routeNumber ?? '').localeCompare(String(b.routeNumber ?? ''), undefined, { numeric: true }));
}

/** A poll older than this means the map is no longer being refreshed. */
export const FEED_STALE_AFTER_MS = 45000;

/**
 * State of the live layer as a whole: 'loading' before the first answer, 'live' while polls keep
 * succeeding, 'stale' when the last success is too old (positions shown are aging out), 'down'
 * when the backend says its vehicle feed is unavailable, 'error' when nothing was ever loaded.
 */
export function feedState({ updatedAt, error, data }, nowMs = Date.now()) {
  if (data?.feedAvailable === false) return 'down';
  if (!updatedAt) return error ? 'error' : 'loading';
  return nowMs - updatedAt > FEED_STALE_AFTER_MS ? 'stale' : 'live';
}

/**
 * Buses heading to any of the nearby stops, one entry per vehicle (its soonest stop), each with
 * a live position. `stops` is the /stops/nearby-regional result's `stops` array.
 */
export function approachingBuses(stops) {
  const byVehicle = new Map();
  for (const stop of stops ?? []) {
    for (const u of stop.upcoming ?? []) {
      if (!Number.isFinite(u.busLatitude) || !Number.isFinite(u.busLongitude)) continue;
      const entry = { ...u, stopId: stop.id, stopName: stop.name, stopLat: stop.lat, stopLng: stop.lng };
      const prev = byVehicle.get(u.vehicleId);
      if (!prev || (u.etaMinutes ?? Infinity) < (prev.etaMinutes ?? Infinity)) byVehicle.set(u.vehicleId, entry);
    }
  }
  return [...byVehicle.values()].sort((a, b) => (a.etaMinutes ?? Infinity) - (b.etaMinutes ?? Infinity));
}
