// Admin console helpers. Pure functions (no React, no DOM) so they can be unit-tested with `node --test`.

// ── Live status ─────────────────────────────────────────────────────────────
// Mirrors BusLiveStatus.java: the admin stats, the fleet list and the live map must classify a bus
// the same way, so change these only together with the backend.
export const STALE_AFTER_SECONDS = 120;
export const SLOW_SPEED_KMH = 5;

/** MOVING, SLOW, STALE or NO_DATA for a /topic/buses event (or anything with timestamp + speedKmh). */
export function liveStatus(event, nowSeconds = Date.now() / 1000) {
  if (!event) return 'NO_DATA';
  if (event.timestamp == null || nowSeconds - event.timestamp > STALE_AFTER_SECONDS) return 'STALE';
  return event.speedKmh != null && event.speedKmh < SLOW_SPEED_KMH ? 'SLOW' : 'MOVING';
}

export const STATUSES = ['MOVING', 'SLOW', 'STALE', 'NO_DATA'];

// "Slow" is only a speed reading: there is no timetable data to call a bus late, so never "delayed".
export const STATUS_META = {
  MOVING: {
    label: 'Moving', tone: 'live',
    hint: 'GPS position in the last 2 minutes, at 5 km/h or more (or no speed reported).',
  },
  SLOW: {
    label: 'Slow / possibly stuck', tone: 'delayed',
    hint: 'Reporting a position, but slower than 5 km/h: in traffic, at a stop or parked. '
      + 'This is a speed reading, not a timetable delay; there is no schedule data to compare against.',
  },
  STALE: {
    label: 'Stale', tone: 'stale',
    hint: 'Last GPS position is more than 2 minutes old.',
  },
  NO_DATA: {
    label: 'No position', tone: 'stale',
    hint: 'Registered, but no live position since the server started.',
  },
};

// ── KPI history (sparklines) ────────────────────────────────────────────────
export const HISTORY_SIZE = 30;

export const KPI_KEYS = ['live', 'offline', 'slow', 'averageSpeedKmh'];

/** One history sample from GET /admin/stats. `at` is epoch milliseconds. */
export const sampleFromStats = (stats, at) => ({
  at,
  live: stats.liveBusCount,
  offline: stats.offlineBusCount,
  slow: stats.delayedBusCount,
  averageSpeedKmh: stats.averageSpeedKmh,
});

/** Appends to a ring buffer of at most `size` samples (returns a new array, oldest first). */
export function pushSample(buffer, sample, size = HISTORY_SIZE) {
  if (buffer.length && buffer[buffer.length - 1].at >= sample.at) return buffer;
  const next = [...buffer, sample];
  return next.length > size ? next.slice(next.length - size) : next;
}

/**
 * Server history (GET /admin/stats/history, 30 s apart) followed by this page's own polls, as one
 * time-ordered buffer of the newest `size` samples.
 */
export function mergeHistory(serverSamples = [], clientSamples = [], size = HISTORY_SIZE) {
  const all = [...serverSamples, ...clientSamples].sort((a, b) => a.at - b.at);
  const out = [];
  for (const s of all) {
    if (out.length && out[out.length - 1].at === s.at) continue;
    out.push(s);
  }
  return out.slice(Math.max(0, out.length - size));
}

/** Change of `key` across the buffer: { delta, sinceMs } or null with fewer than two samples. */
export function trend(samples, key) {
  if (samples.length < 2) return null;
  const first = samples[0], last = samples[samples.length - 1];
  if (first[key] == null || last[key] == null) return null;
  return { delta: Math.round((last[key] - first[key]) * 10) / 10, sinceMs: last.at - first.at };
}

/**
 * SVG path ("M x y L …") for `key` over time, scaled into width × height with `pad` px inside.
 * A flat series is drawn through the middle; fewer than two samples give ''.
 */
export function sparklinePath(samples, key, width, height, pad = 2) {
  const pts = samples.filter(s => Number.isFinite(s[key]));
  if (pts.length < 2) return '';
  const t0 = pts[0].at, t1 = pts[pts.length - 1].at;
  const values = pts.map(s => s[key]);
  const lo = Math.min(...values), hi = Math.max(...values);
  const x = s => pad + (t1 === t0 ? 0 : (s.at - t0) / (t1 - t0)) * (width - 2 * pad);
  const y = v => hi === lo ? height / 2 : pad + (1 - (v - lo) / (hi - lo)) * (height - 2 * pad);
  return pts.map((s, i) => `${i ? 'L' : 'M'}${x(s).toFixed(1)} ${y(s[key]).toFixed(1)}`).join(' ');
}

// ── Session ─────────────────────────────────────────────────────────────────
export const SESSION_WARN_MS = 5 * 60 * 1000;

/** What to tell someone whose login failed (axios error from POST /auth/login). */
export function loginErrorMessage(err) {
  const status = err?.response?.status;
  if (!err?.response) return "Can't reach the server. Check your connection and try again.";
  // LoginAttemptLimiter: 5 failures from one address lock it out for 15 minutes.
  if (status === 429) {
    return 'Too many failed attempts from this network. Login is locked for up to 15 minutes; '
      + 'wait and try again (a correct password will not work until then).';
  }
  if (status === 401) return 'Wrong username or password.';
  if (status === 400) return err.response.data?.message || 'Enter a username and password.';
  return err.response.data?.message || `Login failed (HTTP ${status}).`;
}

export const isAuthError = (error) => error?.status === 401 || error?.status === 403;

/** The JWT's `exp` claim as epoch milliseconds, or null when the token can't be read. */
export function tokenExpiry(token) {
  try {
    const part = String(token).split('.')[1];
    if (!part) return null;
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
    const exp = JSON.parse(json).exp;
    return Number.isFinite(exp) ? exp * 1000 : null;
  } catch {
    return null;
  }
}

/** "23 h 59 min", "4:05" (under an hour: minutes and seconds), "0:00" when past. */
export function countdownText(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s >= 3600) return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

// ── Tables ──────────────────────────────────────────────────────────────────
/** Sorted copy by `key` ('asc' | 'desc'); missing values always go last. Strings compare naturally. */
export function sortBy(rows, key, dir = 'asc') {
  const sign = dir === 'desc' ? -1 : 1;
  return [...rows].sort((a, b) => {
    const va = a[key], vb = b[key];
    if (va == null && vb == null) return 0;
    if (va == null) return 1;
    if (vb == null) return -1;
    if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * sign;
    return String(va).localeCompare(String(vb), 'en', { numeric: true }) * sign;
  });
}

/** Public route number when known, else the GTFS route_id the feed reports. */
export const routeLabel = (r) => r?.routeName || r?.routeCode || '—';

/**
 * CSV text for `rows` with `columns` [{ key, label, value? }]. Quotes every field that needs it, and
 * prefixes cells starting with = + - @ so a spreadsheet does not run them as formulas.
 */
export function toCsv(rows, columns) {
  const cell = (v) => {
    if (v == null) return '';
    let s = String(v);
    if (/^[=+\-@\t\r]/.test(s) && !/^-?\d+(\.\d+)?$/.test(s)) s = `'${s}`;
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const head = columns.map(c => cell(c.label)).join(',');
  const body = rows.map(r => columns.map(c => cell(c.value ? c.value(r) : r[c.key])).join(','));
  return [head, ...body].join('\r\n') + '\r\n';
}

// ── Occupancy ───────────────────────────────────────────────────────────────
/** low (< 60 %), mid (< 85 %) or high load. */
export const loadTone = (percent) => percent >= 85 ? 'high' : percent >= 60 ? 'mid' : 'low';

// ── Alerts ──────────────────────────────────────────────────────────────────
export const SEVERITIES = ['CRITICAL', 'WARNING', 'INFO'];

export function countBySeverity(alerts = []) {
  const counts = { CRITICAL: 0, WARNING: 0, INFO: 0 };
  for (const a of alerts) if (a.severity in counts) counts[a.severity]++;
  return counts;
}

// ── Live positions ──────────────────────────────────────────────────────────
/** A GET /admin/buses row in the shape of a /topic/buses event. */
export const eventFromFleetRow = (row) => ({
  vehicleId: row.vehicleId,
  routeId: row.routeCode,
  latitude: row.latitude,
  longitude: row.longitude,
  speedKmh: row.speedKmh,
  bearing: row.bearing,
  timestamp: row.lastSeen,
  wheelchairSpaceAvailable: row.wheelchairSpaceAvailable,
});

/** Socket positions over the polled ones, keeping whichever fix of a vehicle is newer. */
export function mergePositions(polled = {}, pushed = {}) {
  const out = { ...polled };
  for (const [id, e] of Object.entries(pushed)) {
    const prev = out[id];
    if (!prev || (e.timestamp ?? 0) >= (prev.timestamp ?? 0)) out[id] = e;
  }
  return out;
}

const hasPosition = (e) => Number.isFinite(e?.latitude) && Number.isFinite(e?.longitude);

/** Buses to draw: with a position, on `route` (if given) and in one of the `shown` statuses. */
export function visibleBuses(positions, { route, shown, now }) {
  const out = [];
  for (const e of Object.values(positions)) {
    if (!hasPosition(e)) continue;
    if (route && e.routeId !== route) continue;
    const status = liveStatus(e, now);
    if (!shown.has(status)) continue;
    out.push({ ...e, status });
  }
  return out;
}

export function countStatuses(positions, { route, now }) {
  const counts = { MOVING: 0, SLOW: 0, STALE: 0 };
  for (const e of Object.values(positions)) {
    if (!hasPosition(e) || (route && e.routeId !== route)) continue;
    counts[liveStatus(e, now)]++;
  }
  return counts;
}

// ── Command palette ─────────────────────────────────────────────────────────
/** Items whose label (or keywords) contain every word of `query`; prefix matches first. */
export function searchItems(items, query, limit = 12) {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return items.slice(0, limit);
  const scored = [];
  for (const item of items) {
    const hay = `${item.label} ${item.keywords ?? ''}`.toLowerCase();
    if (!words.every(w => hay.includes(w))) continue;
    scored.push({ item, score: item.label.toLowerCase().startsWith(words[0]) ? 0 : 1 });
  }
  return scored.sort((a, b) => a.score - b.score).slice(0, limit).map(s => s.item);
}
