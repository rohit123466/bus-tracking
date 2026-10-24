// Backend reachability as seen by this tab: every API response or failure reports here, so one
// banner can say "waking up", "reconnecting" or "offline" instead of each page guessing.
// Pure module (no React) so it can be unit-tested with `node --test`.

/** connecting → online, or waking (never answered yet) / reconnecting (answered before) / offline. */
const initial = () => ({ state: 'connecting', lastOkAt: null, lastFailure: null, since: Date.now() });

let snapshot = initial();
let everOk = false;
let wakeTimer = null;
const listeners = new Set();

// The free Render instance sleeps when idle and takes minutes to boot; a first request still
// pending after this long is reported as "waking up" rather than as an error.
export const WAKE_NOTICE_MS = 6000;

function update(next) {
  if (next.state === snapshot.state && next.lastOkAt === snapshot.lastOkAt && next.lastFailure === snapshot.lastFailure) return;
  snapshot = { ...snapshot, ...next };
  listeners.forEach(l => l());
}

const browserOffline = () => typeof navigator !== 'undefined' && navigator.onLine === false;

export function reportRequestStart() {
  if (everOk || wakeTimer || typeof setTimeout === 'undefined') return;
  wakeTimer = setTimeout(() => {
    wakeTimer = null;
    if (!everOk && !browserOffline()) update({ state: 'waking', since: Date.now() });
  }, WAKE_NOTICE_MS);
}

export function reportSuccess(now = Date.now()) {
  everOk = true;
  clearTimeout(wakeTimer);
  wakeTimer = null;
  update({ state: 'online', lastOkAt: now, lastFailure: null, since: snapshot.state === 'online' ? snapshot.since : now });
}

/** `kind` comes from classifyError(); only connectivity failures change the connection state. */
export function reportFailure(kind, now = Date.now()) {
  if (!['timeout', 'unreachable', 'starting', 'offline'].includes(kind)) return;
  if (kind === 'offline' || browserOffline()) { update({ state: 'offline', lastFailure: kind, since: now }); return; }
  const state = everOk ? 'reconnecting' : 'waking';
  update({ state, lastFailure: kind, since: snapshot.state === state ? snapshot.since : now });
}

export function reportBrowserOnline(online) {
  if (!online) update({ state: 'offline', lastFailure: 'offline', since: Date.now() });
  else if (snapshot.state === 'offline') update({ state: everOk ? 'reconnecting' : 'connecting', since: Date.now() });
}

export const getConnection = () => snapshot;

export function subscribeConnection(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => reportBrowserOnline(true));
  window.addEventListener('offline', () => reportBrowserOnline(false));
}

/** Test hook: forget everything. */
export function resetConnection() {
  snapshot = initial();
  everOk = false;
  clearTimeout(wakeTimer);
  wakeTimer = null;
}
