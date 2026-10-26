import { inDelhiNcr } from './geo.js';

export function distance(a, b) {
  const rad = n => n * Math.PI / 180;
  const h = Math.sin(rad(b.lat - a.lat) / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(rad(b.lng - a.lng) / 2) ** 2;
  return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
}
export function progress(coordinates, location) {
  let offset = Infinity, along = 0, total = 0, segment = 0;
  for (let i = 1; i < coordinates.length; i++) {
    const a = { lat: coordinates[i - 1][0], lng: coordinates[i - 1][1] };
    const b = { lat: coordinates[i][0], lng: coordinates[i][1] };
    const scale = Math.cos(location.lat * Math.PI / 180);
    const x = (b.lng - a.lng) * scale, y = b.lat - a.lat;
    const t = Math.max(0, Math.min(1, (x * x + y * y) ? ((location.lng - a.lng) * scale * x + (location.lat - a.lat) * y) / (x * x + y * y) : 0));
    const length = distance(a, b);
    const d = distance(location, { lat: a.lat + t * (b.lat - a.lat), lng: a.lng + t * (b.lng - a.lng) });
    if (d < offset) { offset = d; along = total + t * length; segment = i - 1; }
    total += length;
  }
  return { offset, remaining: Math.max(0, total - along), segment, total };
}
/**
 * Filters and deduplicates live bus positions for rendering.
 *
 * Rules enforced here (all of which the server already applies, but defended
 * again client-side to guard against stale race responses):
 *  1. vehicleId must be a non-empty string.
 *  2. routeCode must exactly match routeNumber when a route is selected.
 *     Both are uppercased before comparison so "534a" === "534A".
 *  3. busLatitude and busLongitude must be valid coordinates inside Delhi NCR
 *     (finite, in range, not the 0,0 "no fix" placeholder).
 *  4. GPS timestamp must be fresh (within 120 s, no more than 30 s in future).
 *  5. Deduplicated by vehicleId — the newest GPS fix wins, so a repeated or
 *     out-of-order record can never move a marker back to an older position.
 *
 * @param {Array} buses - Raw bus array from the API response.
 * @param {string} routeNumber - Selected route public number (e.g. "534"), or "".
 * @param {number} [now] - Current epoch seconds (injectable for tests).
 */
export function visibleBuses(buses, routeNumber, now = Date.now() / 1000) {
  const selectedRoute = routeNumber ? routeNumber.trim().toUpperCase() : '';
  const byId = new Map();
  for (const b of buses ?? []) {
    const ok = b && b.vehicleId &&
      // Exact route match (case-insensitive), or no route selected.
      (!selectedRoute || (b.routeCode != null && b.routeCode.toString().toUpperCase() === selectedRoute) ||
        // A bus whose feed number is stale but whose drive fits the selected route's DTC path.
        (b.likelyRoutes ?? []).some(n => String(n).toUpperCase() === selectedRoute)) &&
      inDelhiNcr(b.busLatitude, b.busLongitude) &&
      b.lastUpdatedEpochSeconds > 0 &&
      now - b.lastUpdatedEpochSeconds <= 120 &&
      now - b.lastUpdatedEpochSeconds >= -30;
    if (!ok) continue;
    const prev = byId.get(b.vehicleId);
    if (!prev || b.lastUpdatedEpochSeconds >= prev.lastUpdatedEpochSeconds) byId.set(b.vehicleId, b);
  }
  return [...byId.values()];
}
