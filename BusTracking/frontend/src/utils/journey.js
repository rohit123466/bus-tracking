// Helpers for /api/transit/journey/plan results. Pure (unit-tested with `node --test`).
import { inDelhiNcr } from './geo.js';

export const PREFERENCES = [
  { value: 'FASTEST', label: 'Fastest' },
  { value: 'FEWEST_TRANSFERS', label: 'Fewest changes' },
  { value: 'MINIMUM_WALKING', label: 'Least walking' },
];

export const BASIS_TEXT = {
  SCHEDULED_GTFS: 'timetable ride time',
  ASSUMED_SPEED: 'estimated at an average bus speed (no timetable times)',
  STRAIGHT_LINE: 'straight-line distance',
};

export const STATUS_TEXT = {
  TOO_CLOSE: 'These places are very close together: walking will be quicker than any bus.',
  NO_STOP_NEAR_START: 'There is no bus stop in the timetable within 1.5 km of the start.',
  NO_STOP_NEAR_DESTINATION: 'There is no bus stop in the timetable within 1.5 km of the destination.',
  NO_CONNECTION: 'No direct bus or single-change connection was found between stops near these places.',
};

export const busSegments = journey => (journey?.segments ?? []).filter(s => s.type === 'BUS');

/**
 * How a journey's total time was obtained: SCHEDULED when every bus leg uses timetable ride
 * times, APPROXIMATE when any leg was estimated at an average speed, UNAVAILABLE without a usable
 * total. Walking is always a straight-line estimate and waiting is never included.
 */
export function journeyDurationBasis(journey) {
  const buses = busSegments(journey);
  if (!buses.length || !Number.isFinite(journey?.totalMinutes)) return 'UNAVAILABLE';
  return buses.every(b => b.basis === 'SCHEDULED_GTFS') ? 'SCHEDULED' : 'APPROXIMATE';
}

export const DURATION_BASIS_TEXT = {
  SCHEDULED: 'Timetable ride times + straight-line walking',
  APPROXIMATE: 'Partly estimated at an average bus speed',
  UNAVAILABLE: 'Journey time unavailable',
};

/** Every coordinate the journey touches, for fitting the map. */
export function journeyPoints(journey) {
  const points = [];
  for (const s of journey?.segments ?? []) {
    const stops = s.type === 'BUS' && s.stops?.length ? s.stops : [s.from, s.to];
    for (const p of stops) if (p && Number.isFinite(p.lat) && Number.isFinite(p.lng)) points.push([p.lat, p.lng]);
  }
  return points;
}

/** "lat,lng|Label" in a URL parameter → place, or null when malformed / outside Delhi NCR. */
export function parsePlaceParam(raw) {
  if (!raw) return null;
  const [coords, ...label] = raw.split('|');
  const [lat, lng] = coords.split(',').map(Number);
  if (!inDelhiNcr(lat, lng)) return null;
  return { lat, lng, label: label.join('|') || 'Selected place', kind: 'place' };
}

export const placeParam = p => `${p.lat.toFixed(6)},${p.lng.toFixed(6)}|${p.label ?? ''}`;
