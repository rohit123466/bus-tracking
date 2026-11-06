// Pick-up -> destination route search helpers (see /api/transit/stands/routes). Pure functions so
// they can be unit-tested with `node --test`. Every route, stop, time and distance shown comes from
// the backend; these only validate input, sort and word what the server sent.

/** Default walk to stops near a selected stand (big terminals spread over several stop names). */
export const DEFAULT_WALK_METERS = 300;

/** Stand IDs (any stop of it) shared by both selections: they are the same stand. */
export function sameStand(a, b) {
  if (!a || !b) return false;
  if (a.id === b.id) return true;
  const ids = new Set(a.stopIds ?? [a.id]);
  return (b.stopIds ?? [b.id]).some(id => ids.has(id));
}

/** Why "Find buses" can't run yet, per field; empty object when both are fine. */
export function validateTrip(from, to) {
  const errors = {};
  if (!from) errors.from = 'Select a pick-up bus stand from the suggestions.';
  if (!to) errors.to = 'Select a destination bus stand from the suggestions.';
  if (from && to && sameStand(from, to)) errors.to = 'Pick-up and destination must be different bus stands.';
  return errors;
}

/** Suggestions without the stand already chosen in the other field. */
export const withoutStand = (stands, other) => (stands ?? []).filter(s => !sameStand(s, other));

export const SORTS = [
  { value: 'best', label: 'Best match' },
  { value: 'time', label: 'Shortest time' },
  { value: 'stops', label: 'Fewest stops' },
  { value: 'live', label: 'Most live buses' },
];

const byNumber = (a, b) => String(a.routeNumber).localeCompare(String(b.routeNumber), undefined, { numeric: true });

/**
 * Routes in the chosen order. "best" keeps the server's order (walk + stops ridden). Unknown
 * values sort last, so a route without a scheduled time is never shown as the fastest.
 */
export function sortRoutes(routes, sort = 'best') {
  const list = [...(routes ?? [])];
  const last = v => (v == null || !Number.isFinite(v) ? Infinity : v);
  if (sort === 'time') list.sort((a, b) => last(a.scheduledMinutes) - last(b.scheduledMinutes) || byNumber(a, b));
  else if (sort === 'stops') list.sort((a, b) => a.stops.length - b.stops.length || byNumber(a, b));
  else if (sort === 'live') list.sort((a, b) => b.liveBusCount - a.liveBusCount || byNumber(a, b));
  return list;
}

/** Stops strictly between pick-up and destination. */
export const intermediateStops = route => (route?.stops ?? []).slice(1, -1);

/** "≈ 12.4 km by road" / "≈ 11.0 km (straight lines between stops)"; null when unknown. */
export function distanceLabel(route) {
  const km = route?.distanceKm;
  if (km == null || !Number.isFinite(km) || km <= 0) return null;
  const text = `≈ ${km < 10 ? km.toFixed(1) : Math.round(km)} km`;
  return route.distanceBasis === 'ROUTE_SHAPE' ? `${text} by road` : `${text} (straight lines between stops)`;
}

/** "120 m walk from Kashmere Gate" when the route calls at a nearby stop, not the stand itself. */
export function walkNote(standName, meters, atStand) {
  if (atStand || !standName) return null;
  return `${Math.round(meters)} m walk from ${standName}`;
}

/** Service day (Delhi time) of an epoch-seconds instant, e.g. "2026-11-06". */
const delhiDay = s => new Date(s * 1000).toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' });

/** "19:56, tomorrow 08:51, 10:16": timetable departures, marking where the next day starts. */
export function departuresText(departures, now = Date.now() / 1000) {
  const today = delhiDay(now);
  let marked = false;
  return (departures ?? []).map((d) => {
    if (marked || delhiDay(d.epochSeconds) === today) return d.time;
    marked = true;
    return `tomorrow ${d.time}`;
  }).join(', ');
}

/** "42 trips a day · 05:30–22:10"; null when the timetable lists none. */
export function timetableText(route) {
  if (!route?.tripsPerDay) return null;
  const span = route.firstDeparture && route.lastDeparture && route.firstDeparture !== route.lastDeparture
    ? ` · ${route.firstDeparture}–${route.lastDeparture}` : route.firstDeparture ? ` · ${route.firstDeparture}` : '';
  return `${route.tripsPerDay} trip${route.tripsPerDay === 1 ? '' : 's'} a day${span}`;
}

/** Live-bus line for a route card; the route is shown either way. */
export function liveCountText(route, feedAvailable = true) {
  if (!feedAvailable) return 'Live feed unavailable';
  const n = route?.liveBusCount ?? 0;
  if (!n) return 'No live buses currently available on this route';
  return `${n} live bus${n === 1 ? '' : 'es'} on this route`;
}

export const PICKUP_STATE_META = {
  HEADING_TO_PICKUP: { label: 'Coming to pick-up', tone: 'badge-live' },
  PASSED_PICKUP: { label: 'Already passed pick-up', tone: '' },
  POSITION_UNKNOWN: { label: 'Position on route unknown', tone: '' },
};

export const pickupStateMeta = s => PICKUP_STATE_META[s] ?? PICKUP_STATE_META.POSITION_UNKNOWN;

/** Live-map link for a route, optionally focused on one bus. */
export function liveMapLink(routeNumber, vehicleId) {
  const p = new URLSearchParams({ route: routeNumber });
  if (vehicleId) p.set('bus', vehicleId);
  return `/?${p}`;
}

/** URL params for a search, so results can be shared and Back works. */
export function tripParams(from, to, walkMeters) {
  const p = { from: from.id, to: to.id };
  if (walkMeters !== DEFAULT_WALK_METERS) p.walk = String(walkMeters);
  return p;
}

/** Walk distance from the URL: 0 or the default; anything else falls back to the default. */
export function parseWalk(raw) {
  if (raw == null || raw === '') return DEFAULT_WALK_METERS;
  const n = Number(raw);
  return Number.isFinite(n) && n >= 0 && n <= 500 ? n : DEFAULT_WALK_METERS;
}

/**
 * A 404 for an unknown stand ("Unknown bus stop ID") versus a 404 because the server has no
 * route-search endpoint at all (an older backend, e.g. one not yet restarted or redeployed: Spring
 * answers "No static resource …"). Only the first means the link is out of date.
 */
export function routeSearchError(error) {
  if (error?.kind !== 'not-found') return 'other';
  return /^Unknown\b/i.test(error.message ?? '') ? 'unknown-stand' : 'unsupported';
}

export const UNSUPPORTED_TEXT =
  'The server you are connected to doesn’t have route search between bus stands yet. Restart (or redeploy) the backend with the latest code, then try again.';

/**
 * Heading for the results: "12 routes · 5 with live buses now", "No direct route · 6 journeys with
 * 2 buses", or both.
 */
export function resultsSummary(result) {
  const n = result?.routes?.length ?? 0;
  const live = result?.routesWithLiveBuses ?? 0;
  const t = result?.transfers?.length ?? 0;
  const direct = n ? `${n} direct route${n === 1 ? '' : 's'} · ${live} with live buses now` : 'No direct route';
  return t ? `${direct} · ${t} journey${t === 1 ? '' : 's'} with 2 buses` : direct;
}

/** Live buses in the shape the map's BusLayer draws (a position is required). */
export function routeMapBuses(buses) {
  return (buses ?? [])
    .filter(b => Number.isFinite(b.busLatitude) && Number.isFinite(b.busLongitude))
    .map(b => ({
      vehicleId: b.vehicleId,
      routeCode: b.routeNumber,
      busLatitude: b.busLatitude,
      busLongitude: b.busLongitude,
      lastUpdatedEpochSeconds: b.lastGpsUpdate,
      routeCheck: b.routeCheck,
      likelyRoutes: b.likelyRoutes ?? [],
    }));
}
