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

export const userIcon = L.divIcon({
  className: 'user-location-marker',
  html: '<div class="user-dot"></div>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
});

export const stopIcon = L.divIcon({
  className: 'stop-marker',
  html: '<div class="stop-dot"></div>',
  iconSize: [12, 12],
  iconAnchor: [6, 6],
});

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
      html: `<div class="bus-chip">${key}</div>`,
      iconSize: [32, 20],
      iconAnchor: [16, 10],
    });
    busIconCache.set(key, icon);
  }
  return icon;
}
