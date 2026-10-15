// Live Map helpers: region filter options, GPS freshness and ETA labels.
// Pure functions so they can be unit-tested with `node --test`.

// `ALL` keeps the original all-routes behaviour; the others hit /api/transit/buses/regional.
export const REGION_OPTIONS = [
  { value: 'ANY', label: 'North or North-West Delhi routes' },
  { value: 'NORTH', label: 'North Delhi routes' },
  { value: 'NORTH_WEST', label: 'North-West Delhi routes' },
  { value: 'ALL', label: 'All Delhi routes' },
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
  UNAVAILABLE: 'unavailable',
};
