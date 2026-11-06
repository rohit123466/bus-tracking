// Bus-stand search & live arrivals helpers (see /api/transit/stands/*). Pure functions so they can
// be unit-tested with `node --test`. Nothing here creates a time or a position: every value shown
// comes from the backend, and timetable entries stay labelled as scheduled.
import { GPS_STALE_AFTER_S } from './liveMap.js';

/** Example searches shown before the first one; they only fill the search box. */
export const EXAMPLE_QUERIES = ['Kashmere Gate', 'Azadpur', 'Rohini Sector 18'];

export const SEARCH_PLACEHOLDER = 'Search your bus stand (e.g. Kashmere Gate, Azadpur, Rohini Sector 18)';

/** Status text and badge tone; the text is always shown, so status never relies on colour alone. */
export const STATUS_META = {
  ARRIVING: { label: 'Arriving', tone: 'badge-live' },
  APPROACHING: { label: 'Approaching', tone: 'badge-info' },
  ON_THE_WAY: { label: 'On the way', tone: '' },
  DELAYED: { label: 'Delayed', tone: 'badge-delayed' },
};

export const statusMeta = status => STATUS_META[status] ?? { label: 'Unknown', tone: '' };

const byRoute = (a, b) => String(a).localeCompare(String(b), undefined, { numeric: true });

/** Combobox option for a stand suggestion: "2 stops · Routes 100, 166, 234 +12 more". */
export function standOption(stand, shown = 5) {
  const routes = [...(stand.routeNumbers ?? [])].sort(byRoute);
  const stops = stand.stopIds?.length ?? 1;
  const parts = [];
  // Set by the backend only when another suggestion has the same name.
  if (stand.hint) parts.push(stand.hint);
  if (stops > 1) parts.push(`${stops} stops`);
  if (!routes.length) parts.push('No routes in the timetable');
  else {
    const more = routes.length - shown;
    parts.push(`Route${routes.length === 1 ? '' : 's'} ${routes.slice(0, shown).join(', ')}${more > 0 ? ` +${more} more` : ''}`);
  }
  return { id: stand.id, label: stand.name, sub: parts.join(' · '), icon: 'pin', stand };
}

/** "0.49 km" with how it was measured; null without a distance. */
export function distanceKmText(arrival) {
  const km = arrival?.distanceKm;
  if (km == null || !Number.isFinite(km)) return null;
  return `${km < 10 ? km.toFixed(2) : km.toFixed(1)} km`;
}

export const DISTANCE_BASIS_TEXT = {
  ALONG_ROUTE: 'along the route',
  STRAIGHT_LINE: 'straight line',
};

/** "Near Azadpur Metro Station", else the GPS coordinates, else that the location is unknown. */
export function locationText(arrival) {
  if (arrival?.currentLocation) return arrival.currentLocation;
  const { busLatitude: lat, busLongitude: lng } = arrival ?? {};
  if (Number.isFinite(lat) && Number.isFinite(lng)) return `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
  return 'Location unavailable';
}

/** Whether the bus's GPS fix has aged past the point where it counts as a live position. */
export function gpsStale(arrival, now = Date.now() / 1000) {
  const t = arrival?.lastGpsUpdate;
  return !Number.isFinite(t) || t <= 0 || now - t > GPS_STALE_AFTER_S;
}

/**
 * Counts for the summary cards, re-evaluated against the ticking clock so a bus whose GPS fix
 * goes stale while refreshes are failing stops counting: { live, approaching, next }.
 */
export function standSummary(data, now = Date.now() / 1000) {
  const fresh = (data?.arrivals ?? []).filter(a => !gpsStale(a, now));
  // A bus finishing its trip here can't be boarded onwards: listed, but not counted as "coming".
  const boardable = fresh.filter(a => !a.terminatesHere);
  return {
    live: fresh.length,
    approaching: boardable.filter(a => a.status === 'ARRIVING' || a.status === 'APPROACHING').length,
    next: boardable.find(a => Number.isFinite(a.etaMinutes)) ?? null,
  };
}

/** Live rows shown before "Show all": busy terminals have hundreds of buses within 90 min. */
export const LIVE_ROWS = 25;

/** Arrivals in the shape the map's BusLayer draws (a live position is required). */
export function mapBuses(arrivals) {
  return (arrivals ?? [])
    .filter(a => Number.isFinite(a.busLatitude) && Number.isFinite(a.busLongitude))
    .map(a => ({
      vehicleId: a.vehicleId,
      routeCode: a.routeNumber,
      busLatitude: a.busLatitude,
      busLongitude: a.busLongitude,
      lastUpdatedEpochSeconds: a.lastGpsUpdate,
      routeCheck: a.routeCheck,
      likelyRoutes: a.likelyRoutes ?? [],
    }));
}

/** "in 12 min" / "due now" for a timetable departure, from its scheduled time and the clock. */
export function scheduledInText(item, now = Date.now() / 1000) {
  const at = item?.scheduledEpochSeconds;
  if (!Number.isFinite(at)) return '';
  const min = Math.round((at - now) / 60);
  if (min <= 0) return 'due now';
  return `in ${min} min`;
}

/** Route numbers serving the stand that have neither a tracked bus nor a timetable departure soon. */
export function quietRoutes(data) {
  const busy = new Set([...(data?.arrivals ?? []), ...(data?.scheduled ?? [])].map(a => a.routeNumber));
  return (data?.routeNumbers ?? []).filter(r => !busy.has(r)).sort(byRoute);
}
