// Helpers for /api/transit/journey/plan results. Pure (unit-tested with `node --test`).

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
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || lat < 27 || lat > 30 || lng < 75 || lng > 79) return null;
  return { lat, lng, label: label.join('|') || 'Selected place', kind: 'place' };
}

export const placeParam = p => `${p.lat.toFixed(6)},${p.lng.toFixed(6)}|${p.label ?? ''}`;
