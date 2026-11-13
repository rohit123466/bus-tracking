import axios from 'axios';
import { expandRegional } from '../utils/regionalWire';
import { classifyError, isAbort } from './errors';
import { reportFailure, reportRequestStart, reportSuccess } from './connection';
import { groupStands } from '../utils/liveMap';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export const WS_BASE = API_BASE.replace(/\/api\/?$/, '') + '/ws';

// Every request gets a timeout so a sleeping or hung server surfaces as an error the UI can retry,
// instead of a spinner that never ends.
const client = axios.create({ baseURL: API_BASE, timeout: 30000 });

const ADMIN_TOKEN_KEY = 'dtc_admin_token';

client.interceptors.request.use((config) => {
  reportRequestStart();
  if (config.url?.startsWith('/admin') || config.url?.startsWith('/conductor')) {
    const token = localStorage.getItem(ADMIN_TOKEN_KEY);
    if (token) config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Any HTTP answer except a gateway error proves the backend is up; network failures, timeouts and
// gateway errors (Render returns 502/503 while the instance boots) mean it is not.
client.interceptors.response.use(
  (res) => { reportSuccess(); return res; },
  (err) => {
    if (!isAbort(err)) {
      const { kind, status } = classifyError(err);
      if (status && ![502, 503, 504].includes(status)) reportSuccess();
      else reportFailure(kind);
    }
    return Promise.reject(err);
  },
);

export const getAdminToken = () => localStorage.getItem(ADMIN_TOKEN_KEY);
export const setAdminToken = (token) => localStorage.setItem(ADMIN_TOKEN_KEY, token);
export const clearAdminToken = () => localStorage.removeItem(ADMIN_TOKEN_KEY);

export const getNearbyBuses = (lat, lng, radiusMeters = 5000, limit = 30) =>
  client
    .get('/buses/nearby', { params: { lat, lng, radiusMeters, limit } })
    .then((res) => res.data);

export const getBusByVehicleId = (vehicleId) =>
  client.get(`/buses/${encodeURIComponent(vehicleId)}`).then((res) => res.data);

export const getBusHistory = (vehicleId) =>
  client.get(`/buses/${encodeURIComponent(vehicleId)}/history`).then((res) => res.data);

export const getRoutes = () => client.get('/routes').then((res) => res.data);

// Stops are paginated on the backend; pull a large page once so routes/stop
// clicks can be resolved to lat/lng entirely on the client.
export const getAllStops = (size = 1000) =>
  client.get('/stops', { params: { page: 0, size } }).then((res) => res.data.content);

export const getNearbyStops = (lat, lng, radiusMeters = 5000, limit = 20) =>
  client
    .get('/stops/nearby', { params: { lat, lng, radiusMeters, limit } })
    .then((res) => res.data);

export const getRoutesServingStop = (stopId) =>
  client.get(`/stops/${stopId}/routes`).then((res) => res.data);

export const planJourney = (sourceLat, sourceLng, destinationLat, destinationLng) =>
  client
    .post('/journey/plan', { sourceLat, sourceLng, destinationLat, destinationLng })
    .then((res) => res.data);

const NOMINATIM_URL = 'https://nominatim.openstreetmap.org/search';
// Biases geocoding results toward Delhi/NCR, the region this tracker covers.
const DELHI_VIEWBOX = '76.70,28.95,77.55,28.30';

// Place-name search for the journey planner, backed by OpenStreetMap's public
// Nominatim geocoder (no backend involvement needed for this).
export const searchPlaces = (query, limit = 5, signal) =>
  axios
    .get(NOMINATIM_URL, {
      signal,
      timeout: 10000,
      params: {
        q: query,
        format: 'jsonv2',
        limit,
        viewbox: DELHI_VIEWBOX,
        bounded: 1,
        countrycodes: 'in',
      },
    })
    .then((res) =>
      res.data.map((place) => ({
        label: place.display_name,
        lat: Number(place.lat),
        lng: Number(place.lon),
      }))
    );

export const adminLogin = (username, password) =>
  client.post('/auth/login', { username, password }).then((res) => res.data);

export const getAdminStats = (signal) => client.get('/admin/stats', { signal }).then((res) => res.data);

/** KPI samples from the last two hours (server-side, every 30 s), oldest first. */
export const getAdminStatsHistory = (signal) =>
  client.get('/admin/stats/history', { signal }).then((res) => res.data);

/** One page of the fleet list: { q, status, route, sort, page, size } → { content, totalElements, statusCounts, … }. */
export const getAdminBuses = (params, signal) =>
  client.get('/admin/buses', { params, signal }).then((res) => res.data);

/** Every page of the fleet list for `params` (up to `maxPages` of 500), e.g. for CSV export. */
export async function getAllAdminBuses(params, signal, maxPages = 20) {
  // The first page says how many there are; the rest are fetched at once rather than one by one.
  const first = await getAdminBuses({ ...params, page: 0, size: 500 }, signal);
  const pages = Math.min(first.totalPages, maxPages);
  const rest = await Promise.all(
    Array.from({ length: Math.max(0, pages - 1) }, (_, i) => getAdminBuses({ ...params, page: i + 1, size: 500 }, signal)),
  );
  const rows = [first, ...rest].flatMap((res) => res.content);
  if (first.totalPages <= maxPages) return { rows, total: first.totalElements, complete: true };
  return { rows, total: rows.length, complete: false };
}

export const getFeedHealth = (signal) => client.get('/admin/feed-health', { signal }).then((res) => res.data);

/** Routes with buses that went silent today, most first: { routes, totalRoutes, generatedAt, serviceDayStart }. */
export const getNotRespondingRoutes = (limit, signal) =>
  client.get('/admin/routes/not-responding', { params: { limit }, signal }).then((res) => res.data);

/** Why each non-reporting bus on one route (GTFS route_id) is silent; see RouteDiagnosisService.RouteReport. */
export const getRouteDiagnosis = (routeCode, signal) =>
  client.get(`/admin/routes/${encodeURIComponent(routeCode)}/diagnosis`, { signal }).then((res) => res.data);

// Spring Boot actuator lives outside /api and outside its CORS rules, so this may fail in a browser
// on another origin. Plain axios: such a failure must not mark the whole API as unreachable.
export const getActuatorHealth = (signal) =>
  axios.get(`${API_BASE.replace(/\/api\/?$/, '')}/actuator/health`, { signal, timeout: 10000 }).then((res) => res.data);

// --- Seat-level ticketing (public) ---
export const getTicketingBuses = (signal) => client.get('/ticketing/buses', { signal }).then((res) => res.data);

export const getSeatMap = (busNumber, { fromStopId, toStopId, category } = {}) =>
  client
    .get(`/ticketing/buses/${encodeURIComponent(busNumber)}/seats`, {
      params: { fromStopId, toStopId, category },
    })
    .then((res) => res.data);

// --- Conductor console (admin JWT) ---
export const getPassengers = () => client.get('/conductor/passengers').then((res) => res.data);

export const getBusTickets = (busNumber) =>
  client.get(`/conductor/buses/${encodeURIComponent(busNumber)}/tickets`).then((res) => res.data);

export const sellTicket = (request) => client.post('/conductor/tickets', request).then((res) => res.data);

export const completeTicket = (ticketNumber, atStopId) =>
  client
    .post(`/conductor/tickets/${ticketNumber}/complete`, null, { params: atStopId ? { atStopId } : {} })
    .then((res) => res.data);

export const cancelTicket = (ticketNumber) =>
  client.post(`/conductor/tickets/${ticketNumber}/cancel`).then((res) => res.data);

export const advanceBus = (busNumber) =>
  client.post(`/conductor/buses/${encodeURIComponent(busNumber)}/advance`).then((res) => res.data);

export const getCameraReport = (busNumber) =>
  client.get(`/conductor/buses/${encodeURIComponent(busNumber)}/camera`).then((res) => res.data);

export const simulateCameraScan = (busNumber) =>
  client.post(`/conductor/buses/${encodeURIComponent(busNumber)}/camera/scan`).then((res) => res.data);

export const reportCameraObservations = (busNumber, observations, source = 'MANUAL') =>
  client
    .post(`/conductor/buses/${encodeURIComponent(busNumber)}/camera/observations`, { observations, source })
    .then((res) => res.data);

// Live console view: trip, seats, standing area, seat availability and ticket sales.
export const getLiveOccupancy = (busNumber, signal) =>
  client.get(`/conductor/buses/${encodeURIComponent(busNumber)}/live`, { signal }).then((res) => res.data);

// Standing-area preset played on a camera bus; null pauses the preset feed (device input only).
export const selectCameraPreset = (busNumber, presetId) =>
  client.put(`/conductor/buses/${encodeURIComponent(busNumber)}/camera/preset`, { presetId }).then((res) => res.data);

// One standing-area frame from a camera / IoT device: { sequence?, capturedAt?, source?, detections: [{ trackId, role?, confidence? }] }.
export const reportStandingFrame = (busNumber, frame) =>
  client.post(`/conductor/buses/${encodeURIComponent(busNumber)}/camera/standing-frames`, frame).then((res) => res.data);

// Restores the preset tickets, passengers, camera scans and bus positions.
export const resetTicketingDemo = () => client.post('/conductor/demo/reset').then((res) => res.data);

/** Backend error message for display ("Seat 05 is already occupied from B to C."). */
export const apiErrorMessage = (err, fallback = 'Request failed.') =>
  err?.response?.data?.message || (err?.response ? fallback : 'Cannot reach the server.');

export default client;

const TRANSIT_TIMEOUT_MS = 60000;
const inflight = new Map();

function canceled() {
  const e = new Error('canceled');
  e.name = 'CanceledError';
  e.code = 'ERR_CANCELED';
  return e;
}

/**
 * GET shared by every caller asking for the same URL at the same time (e.g. two panels polling the
 * same stop), so the server sees one request. The shared request is aborted only when every caller
 * has aborted.
 */
function sharedGet(url, params, signal) {
  return sharedResponse(url, params, signal).then(res => res.data);
}

/** sharedGet, resolving with the whole axios response (for its headers). */
function sharedResponse(url, params, signal) {
  const clean = Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null));
  const key = `${url}?${new URLSearchParams(clean)}`;
  let entry = inflight.get(key);
  if (!entry) {
    const ctrl = new AbortController();
    entry = { ctrl, users: 0 };
    entry.promise = client
      .get(url, { params: clean, signal: ctrl.signal, timeout: TRANSIT_TIMEOUT_MS })
      .finally(() => { if (inflight.get(key) === entry) inflight.delete(key); });
    inflight.set(key, entry);
  }
  const shared = entry;
  shared.users++;
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (fn, value) => {
      if (settled) return;
      settled = true;
      signal?.removeEventListener('abort', onAbort);
      fn(value);
    };
    const onAbort = () => {
      if (--shared.users === 0) {
        if (inflight.get(key) === shared) inflight.delete(key);
        shared.ctrl.abort();
      }
      finish(reject, canceled());
    };
    if (signal?.aborted) { onAbort(); return; }
    signal?.addEventListener('abort', onAbort, { once: true });
    shared.promise.then(v => finish(resolve, v), e => finish(reject, e));
  });
}

// Full-feed rider API uses GTFS string IDs, not legacy database IDs.
export const transitGet = (path, params = {}, signal) => sharedGet(`/transit${path}`, params, signal);

// Answer -> ms until the server expects its next feed snapshot (X-Next-Update-In), if it said.
const nextUpdateHints = new WeakMap();
// /buses/regional answer -> { etag, waitMax }: which answer it is, and how long the server is
// willing to hold a request for the next one (X-Wait-Max; absent on servers that cannot).
const regionalAnswers = new WeakMap();

/** How long the Live Map asks the server to hold a request for the next answer. */
const REGIONAL_WAIT_MS = 20000;
/** Pause between getting an answer and asking for the next, so the map draws first. */
const REGIONAL_WAIT_GAP_MS = 250;

/** The answer's data, remembering its X-Next-Update-In hint for regionalPollDelay. */
function withUpdateHint(res) {
  const hint = Number(res.headers?.['x-next-update-in']);
  if (res.data && typeof res.data === 'object' && Number.isFinite(hint)) nextUpdateHints.set(res.data, hint);
  return res.data;
}

/**
 * Live buses on North / North-West routes currently inside the area (optionally one route).
 *
 * Asks for the compact encoding (expanded here into the usual { buses: [...] } shape). With
 * `previous` — the answer this caller already shows — and a server that supports it, the request
 * is held by the server until the answer changes, so new positions arrive as soon as they exist
 * rather than at the next guess of a poll; if nothing changed in time, `previous` is returned
 * as is. usePolling passes `previous` to its fetcher.
 */
export const getRegionalBuses = ({ region = 'ANY', routeNumber = '' } = {}, signal, previous) => {
  const held = previous ? regionalAnswers.get(previous) : null;
  const wait = held?.etag && held.waitMax > 0
    ? { known: held.etag.replace(/"/g, ''), waitMs: Math.min(held.waitMax, REGIONAL_WAIT_MS) }
    : {};
  return sharedResponse('/transit/buses/regional', { region, routeNumber, format: 'compact', ...wait }, signal)
    .then((res) => {
      // 204: nothing changed while the server held the request. Keep showing what we have.
      if (res.status === 204 && previous) return previous;
      const data = expandRegional(res.data);
      const waitMax = Number(res.headers?.['x-wait-max']);
      if (data && typeof data === 'object') {
        regionalAnswers.set(data, { etag: res.headers?.etag, waitMax: Number.isFinite(waitMax) ? waitMax : 0 });
      }
      return withUpdateHint({ headers: res.headers, data });
    });
};

/**
 * Bus stands whose name matches `query` (partial, any case), each stand once:
 * [{ id, name, lat, lng, stopIds, routeNumbers, routeCount }].
 */
export const searchStands = (query, signal) => transitGet('/stands/search', { query, limit: 8 }, signal);

/**
 * Buses heading to the stand containing `stopId`: live GPS arrivals with ETA, distance,
 * destination and status, plus timetable departures labelled SCHEDULED. Poll with
 * regionalPollDelay(data, 12000): the answer carries the server's next-snapshot hint.
 */
export const getStandArrivals = (stopId, signal) =>
  sharedResponse(`/transit/stands/${encodeURIComponent(stopId)}/arrivals`, {}, signal).then(withUpdateHint);

/**
 * Every route whose timetable stop order runs from the stand containing `from` to the stand
 * containing `to`: { status, message, from, to, routes: [{ routeNumber, routeIds, stops, ... }] }.
 * `walkMeters` (0–500) also admits stops that close to either stand.
 */
export const findStandRoutes = ({ from, to, walkMeters = 0 }, signal) =>
  transitGet('/stands/routes', { from, to, walkMeters }, signal);

/**
 * Live buses on one matched route (its route IDs), with position, next stop and ETA at the pick-up
 * stop where one can be estimated. Poll with regionalPollDelay(data, 12000).
 */
export const getStandRouteLive = ({ pickup, routeIds }, signal) =>
  sharedResponse('/transit/stands/routes/live', { pickup, routeIds: routeIds.join(',') }, signal).then(withUpdateHint);

/**
 * When to poll /buses/regional again. For an answer from a server that holds requests until the
 * next change (see getRegionalBuses): almost at once. Otherwise just after the server's next
 * feed snapshot is due (it builds the answer right then), instead of a fixed 10 s that lands up
 * to 10 s after new positions. Falls back to `fallback` for a server that sends no hint.
 */
export function regionalPollDelay(data, fallback = 10000) {
  // The server holds the next request until there is something new: ask again right away.
  const held = regionalAnswers.get(data);
  if (held?.etag && held.waitMax > 0) return REGIONAL_WAIT_GAP_MS;
  const hint = nextUpdateHints.get(data);
  if (hint == null) return fallback;
  return Math.min(15000, Math.max(2000, hint + 1000));
}

/**
 * Nearby stops inside the area with every tracked upcoming bus and its labelled ETA. With
 * `routeNumber`, only that route's stops and arrivals (an older backend ignores it and sends all).
 */
export const getRegionalStops = ({ lat, lng, radiusMeters = 1000, limit = 60, region = 'ANY', routeNumber = '' }, signal) =>
  transitGet('/stops/nearby-regional', {
    latitude: lat, longitude: lng, radiusMeters, limit, region, ...(routeNumber && { routeNumber }),
  }, signal);

export const searchTransitRoutes = (query, signal) => transitGet('/routes/search', { query }, signal);

export const searchTransitStops = (query, routeNumber = '', signal) =>
  transitGet('/stops/search', { query, routeNumber }, signal);

export const getLearningStatus = signal => transitGet('/learning/status', {}, signal);

/**
 * Route geometry for the map. Prefers ordered stop patterns per direction (/routes/patterns); a
 * backend without that endpoint falls back to the unordered stop list of /routes/map, in which case
 * no path or direction can be drawn.
 */
export async function getRouteGeometry(routeNumber, signal) {
  try {
    const patterns = await transitGet('/routes/patterns', { routeNumber }, signal);
    return { source: 'PATTERNS', patterns, stops: dedupeStops(patterns.flatMap(p => p.stops)) };
  } catch (err) {
    if (classifyError(err).kind !== 'not-found') throw err;
  }
  const map = await transitGet('/routes/map', { routeNumber }, signal);
  return { source: 'STOPS_ONLY', patterns: [], stops: map.stops ?? [], shapes: map.shapes ?? [] };
}

function dedupeStops(stops) {
  return [...new Map(stops.map(s => [s.id, s])).values()];
}

/** Stops closer than this to a stop are fetched with it, so the whole bus stand is found. */
const STAND_RADIUS_METERS = 150;

/**
 * Upcoming buses at one stop, merged with the other stops of its stand. Uses the regional nearby
 * endpoint because it is the one that resolves ETAs for today's feed (/stops/{id}/arrivals needs
 * trip IDs the static timetable lacks). `stand` is null when the stop is outside the covered area.
 */
export async function getStopArrivals(stop, signal) {
  const res = await getRegionalStops({ lat: stop.lat, lng: stop.lng, radiusMeters: STAND_RADIUS_METERS }, signal);
  const stands = groupStands(res.stops);
  const stand = stands.find(s => s.members.some(m => m.id === stop.id))
    ?? stands.find(s => s.name.toLowerCase() === (stop.name ?? '').trim().toLowerCase())
    ?? null;
  return { stand, feedAvailable: res.feedAvailable, warnings: res.warnings ?? [] };
}

/** GET /api/transit/journey/plan — direct and one-change journeys over the GTFS stop patterns. */
export const planTransitJourney = ({ from, to, preference = 'FASTEST' }, signal) =>
  transitGet('/journey/plan', {
    fromLat: from.lat, fromLng: from.lng, toLat: to.lat, toLng: to.lng, preference,
  }, signal);

/** GET /api/transit/buses/nearby — GTFS-verified live buses near a GPS point */
export const getNearbyLiveBuses = (lat, lng, radiusMeters = 2000, routeNumber = '') =>
  transitGet('/buses/nearby', { latitude: lat, longitude: lng, radiusMeters, routeNumber });

/** GET /api/transit/stops/nearby — enriched nearest bus stands with routes + live count */
export const getNearbyGtfsStops = (lat, lng, radiusMeters = 1000, limit = 5, signal) =>
  transitGet('/stops/nearby', { latitude: lat, longitude: lng, radiusMeters, limit }, signal);

/** GET /api/transit/alerts — service alerts, optionally filtered to a route */
export const getServiceAlerts = (routeNumber = '', signal) =>
  transitGet('/alerts', { routeNumber }, signal);

/** POST /api/journey/plan with preference */
export const planJourneyWithPreference = (srcLat, srcLng, dstLat, dstLng, preference = 'FASTEST') =>
  client
    .post('/journey/plan', { sourceLat: srcLat, sourceLng: srcLng, destinationLat: dstLat, destinationLng: dstLng },
      { params: { preference } })
    .then((res) => res.data);
