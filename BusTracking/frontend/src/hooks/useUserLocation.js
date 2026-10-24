import { useEffect, useSyncExternalStore } from 'react';
import { toLocation } from '../utils/geo.js';

// One GPS subscription for the whole app, shared by every page that shows the user's position, and
// stopped when no page needs it. It never prompts on its own: it starts automatically only when the
// browser already granted permission, otherwise when the user taps "Use my location" (locate()).
// The position is kept in memory only; nothing is persisted or sent anywhere by this module.

const supported = typeof navigator !== 'undefined' && !!navigator.geolocation;
const WATCH_OPTIONS = { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 };

const MESSAGES = {
  1: 'Location access is blocked. Allow it in your browser’s site settings, then try again.',
  2: 'Your device could not determine a location. Check that location services are on.',
  3: 'Getting a GPS fix took too long. Try again, ideally outdoors or near a window.',
};

/** status: checking | idle (not asked yet) | locating | ready | denied | error | unsupported */
let state = { status: supported ? 'checking' : 'unsupported', location: null, error: supported ? '' : 'This browser cannot share your location.' };
let watchId = null;
let users = 0;
let consented = false;
let checked = false;
const listeners = new Set();

function set(patch) {
  state = { ...state, ...patch };
  listeners.forEach(l => l());
}

const INVALID_FIX = 'Your device reported a location that is not valid. Try again in a moment.';

/** False when the reading was unusable; the last good position (if any) is kept. */
function onPosition(p) {
  const location = toLocation(p);
  if (!location) {
    set({ status: state.location ? 'ready' : 'error', error: INVALID_FIX });
    return false;
  }
  set({ status: 'ready', location, error: '' });
  return true;
}

function onError(e) {
  if (e.code === 1) {
    consented = false;
    stopWatch();
    set({ status: 'denied', error: MESSAGES[1] });
  } else {
    // Keep the last known position (marked with its own timestamp) rather than dropping it.
    set({ status: state.location ? 'ready' : 'error', error: MESSAGES[e.code] ?? MESSAGES[2] });
  }
}

function startWatch() {
  if (!supported || watchId != null || users === 0 || !consented) return;
  watchId = navigator.geolocation.watchPosition(onPosition, onError, WATCH_OPTIONS);
}

function stopWatch() {
  if (watchId == null) return;
  navigator.geolocation.clearWatch(watchId);
  watchId = null;
}

function applyPermission(permission) {
  if (permission === 'granted') {
    consented = true;
    if (!state.location) set({ status: 'locating', error: '' });
    startWatch();
  } else if (permission === 'denied') {
    consented = false;
    stopWatch();
    set({ status: 'denied', error: MESSAGES[1] });
  } else if (!consented) {
    set({ status: 'idle', error: '' });
  }
}

async function checkPermission() {
  if (checked || !supported) return;
  checked = true;
  try {
    const p = await navigator.permissions.query({ name: 'geolocation' });
    applyPermission(p.state);
    p.addEventListener?.('change', () => applyPermission(p.state));
  } catch {
    // No Permissions API (older Safari): wait for an explicit request.
    if (state.status === 'checking') set({ status: 'idle' });
  }
}

/** Asks for a fresh fix (prompting if needed) and starts following the user. */
export function locate() {
  return new Promise(resolve => {
    if (!supported) { resolve(null); return; }
    consented = true;
    if (!state.location) set({ status: 'locating', error: '' });
    navigator.geolocation.getCurrentPosition(p => {
      const ok = onPosition(p);
      startWatch();
      resolve(ok ? state.location : null);
    }, e => { onError(e); resolve(null); }, { enableHighAccuracy: true, maximumAge: 0, timeout: 20000 });
  });
}

const subscribe = l => { listeners.add(l); return () => listeners.delete(l); };
const snapshot = () => state;

/** Current { status, location, error } outside React (tests, non-component code). */
export const getLocationState = snapshot;

export default function useUserLocation() {
  const current = useSyncExternalStore(subscribe, snapshot, snapshot);
  useEffect(() => {
    users++;
    checkPermission();
    startWatch();
    return () => {
      users--;
      if (users === 0) stopWatch();
    };
  }, []);
  return { ...current, locate, locating: current.status === 'locating' };
}
