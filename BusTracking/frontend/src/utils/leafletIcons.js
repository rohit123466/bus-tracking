import L from 'leaflet';
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

// react-leaflet ships without its default marker images wired up under
// bundlers like Vite/webpack; point the default icon at the real assets.
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow,
});

// Escapes text dropped into a divIcon's raw HTML string (stop names/route
// codes come from GTFS data, but this is cheap insurance against markup
// sneaking in through an untrusted feed).
function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export const userIcon = L.divIcon({
  className: 'user-location-marker',
  html: '<div class="user-dot" role="img" aria-label="Your current location"></div>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
});

// Generic marker (no per-stop label) kept for callers that don't have a stop name handy.
export const stopIcon = L.divIcon({
  className: 'stop-marker',
  html: '<div class="stop-dot" role="img" aria-label="Bus stop"></div>',
  iconSize: [12, 12],
  iconAnchor: [6, 6],
});

// Per-stop icon carrying the stop's name in its aria-label, so screen readers
// announce which stop a marker represents instead of a generic "Bus stop".
export function stopIconFor(name) {
  return L.divIcon({
    className: 'stop-marker',
    html: `<div class="stop-dot" role="img" aria-label="Bus stop: ${escapeHtml(name || 'unnamed')}"></div>`,
    iconSize: [12, 12],
    iconAnchor: [6, 6],
  });
}

// Bus positions stream in continuously (websocket-pushed), but a given
// vehicle's routeCode is stable across updates - cache by routeCode so we
// don't reallocate a divIcon on every position tick for every bus.
const busIconCache = new Map();

export function busIcon(routeCode) {
  const key = routeCode ?? '?';
  let icon = busIconCache.get(key);
  if (!icon) {
    icon = L.divIcon({
      className: 'bus-marker',
      html: `<div class="bus-chip" role="img" aria-label="Bus on route ${escapeHtml(key)}">${escapeHtml(key)}</div>`,
      iconSize: [32, 20],
      iconAnchor: [16, 10],
    });
    busIconCache.set(key, icon);
  }
  return icon;
}
