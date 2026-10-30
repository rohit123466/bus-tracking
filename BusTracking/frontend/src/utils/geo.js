// Coordinate validation shared by bus positions, the user's GPS fix and URL parameters.
// Pure functions so they can be unit-tested with `node --test`.

/** Finite, within the globe's ranges, and not (0, 0) — the usual "no fix" placeholder. */
export function isValidCoordinate(lat, lng) {
  return Number.isFinite(lat) && Number.isFinite(lng)
    && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
    && !(lat === 0 && lng === 0);
}

/** Generous Delhi NCR box: a DTC bus or rider outside it is a bad fix, not a real position. */
export const NCR_BOUNDS = { south: 27, north: 30, west: 75, east: 79 };

export function inDelhiNcr(lat, lng) {
  return isValidCoordinate(lat, lng)
    && lat >= NCR_BOUNDS.south && lat <= NCR_BOUNDS.north
    && lng >= NCR_BOUNDS.west && lng <= NCR_BOUNDS.east;
}

/** Great-circle (haversine) distance in metres between two { lat, lng } points. */
export function metresBetween(a, b) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.sqrt(h));
}

/** A browser fix less precise than this is shown, but flagged as approximate. */
export const COARSE_ACCURACY_M = 500;

/**
 * Browser GeolocationPosition → { lat, lng, accuracy, timestamp, coarse }, or null when the
 * reading is unusable (missing or out-of-range coordinates). Accuracy is kept only when it is a
 * real non-negative number.
 */
export function toLocation(position) {
  const c = position?.coords;
  if (!c || !isValidCoordinate(c.latitude, c.longitude)) return null;
  const accuracy = Number.isFinite(c.accuracy) && c.accuracy >= 0 ? c.accuracy : null;
  return {
    lat: c.latitude,
    lng: c.longitude,
    accuracy,
    timestamp: Number.isFinite(position.timestamp) ? position.timestamp : Date.now(),
    coarse: accuracy == null || accuracy > COARSE_ACCURACY_M,
  };
}

/** Default map view: central Delhi at a zoom that shows most of the city. */
export const DELHI_CENTER = [28.6139, 77.209];
export const DELHI_ZOOM = 12;
