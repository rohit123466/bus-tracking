// Arrival alerts: "tell me when route X is N minutes from stop Y". Watches persist in localStorage;
// which buses were already announced is kept in localStorage too, so a reload or a second open tab
// does not repeat them (notifications also carry a tag, so the OS replaces rather than stacks).
// Alerts are delivered only while a tab of the app is open: there is no push server.
import { createPersistentStore } from './persistent.js';

export const MAX_WATCHES = 5;
export const THRESHOLD_OPTIONS = [2, 3, 5, 7, 10, 15];
/** An ETA computed from a GPS fix older than this is not announced. */
const MAX_GPS_AGE_S = 120;
/** Announcements are forgotten after this, so a bus on its next trip can be announced again. */
const NOTIFIED_TTL_MS = 2 * 60 * 60 * 1000;

const isWatch = w => w && typeof w.stopId === 'string' && Number.isFinite(w.lat) && Number.isFinite(w.lng);

export const watches = createPersistentStore('dtc_arrival_watches', [], v => Array.isArray(v) && v.every(isWatch));

export const watchId = (stopId, routeNumber) => `${stopId}|${routeNumber || '*'}`;

export function addWatchTo(list, { stop, routeNumber = null, thresholdMinutes = 5 }, now = Date.now()) {
  const id = watchId(stop.id, routeNumber);
  const rest = list.filter(w => w.id !== id);
  const watch = {
    id, stopId: stop.id, stopName: stop.name, lat: stop.lat, lng: stop.lng,
    routeNumber: routeNumber || null, thresholdMinutes, createdAt: now,
  };
  return [...rest, watch].slice(-MAX_WATCHES);
}

export const addWatch = opts => watches.set(list => addWatchTo(list, opts));
export const removeWatch = id => watches.set(list => list.filter(w => w.id !== id));

/**
 * Upcoming buses at the watched stand that are due to be announced: matching route, a real
 * estimated ETA within the threshold, a fresh GPS fix, and not announced before.
 */
export function dueAnnouncements(watch, upcoming, announced, nowSeconds = Date.now() / 1000) {
  const out = [];
  for (const u of upcoming ?? []) {
    if (watch.routeNumber && u.routeNumber !== watch.routeNumber) continue;
    if (u.etaMinutes == null || !Number.isFinite(u.etaMinutes) || u.etaMinutes > watch.thresholdMinutes) continue;
    if (u.availability === 'UNAVAILABLE') continue;
    if (!u.lastGpsUpdate || nowSeconds - u.lastGpsUpdate > MAX_GPS_AGE_S) continue;
    const key = `${watch.id}|${u.vehicleId}`;
    if (announced.has(key) || out.some(o => o.key === key)) continue;
    const minutes = Math.max(0, Math.round(u.etaMinutes));
    const exact = u.availability === 'TRIP_UPDATE';
    out.push({
      key,
      title: `Route ${u.routeNumber} ${minutes < 1 ? 'arriving now' : `${exact ? '' : 'about '}${minutes} min away`}`,
      body: `${watch.stopName}${exact ? '' : ' · estimated from live GPS, may change'}`,
    });
  }
  return out;
}

const NOTIFIED_KEY = 'dtc_notified';

/** Keys already announced in this browser session, pruned of old entries. */
export function loadAnnounced(now = Date.now()) {
  try {
    const raw = JSON.parse(globalThis.localStorage?.getItem(NOTIFIED_KEY) ?? '{}');
    return new Map(Object.entries(raw).filter(([, at]) => now - at < NOTIFIED_TTL_MS));
  } catch {
    return new Map();
  }
}

export function saveAnnounced(map) {
  try { globalThis.localStorage?.setItem(NOTIFIED_KEY, JSON.stringify(Object.fromEntries(map))); } catch { /* memory only */ }
}
