// divIcons for the passenger map. Styled by .pin-* classes in styles/components.css so they follow
// the light/dark theme. Every icon carries an aria-label (route, state) for screen readers.
import L from 'leaflet';
import { escapeHtml } from './leafletIcons';

/** Canvas paths can't read CSS variables, so resolve the theme colours they need. */
export function themeColors() {
  if (typeof document === 'undefined') return {};
  const css = getComputedStyle(document.documentElement);
  const v = name => css.getPropertyValue(name).trim();
  return {
    live: v('--live'), delayed: v('--delayed'), surface: v('--surface'), text: v('--text-2'),
    route0: v('--route-0'), route1: v('--route-1'), walk: v('--walk'), brand: v('--brand'),
  };
}

const cache = new Map();

const STATE_TEXT = { LIVE: 'live GPS', DELAYED: 'GPS delayed' };

/**
 * `unconfirmed`: the bus's position doesn't fit its route number (routeCheck OFF_ROUTE).
 * `likely`: `routeCode` is the route it is probably running, estimated from GPS, not the feed's.
 */
export function busPin(routeCode, freshness = 'LIVE', selected = false, unconfirmed = false, likely = false) {
  const label = String(routeCode ?? '?');
  const key = `bus|${label}|${freshness}|${selected}|${unconfirmed}|${likely}`;
  let icon = cache.get(key);
  if (!icon) {
    const text = unconfirmed ? `${label}?` : label;
    const width = Math.max(34, text.length * (selected ? 9 : 8) + (selected ? 24 : 18));
    const height = selected ? 30 : 24;
    const cls = `pin-bus${freshness === 'DELAYED' ? ' pin-bus-delayed' : ''}${unconfirmed ? ' pin-bus-unconfirmed' : ''}${selected ? ' pin-bus-selected' : ''}`;
    const route = likely
      ? `Bus likely on route ${escapeHtml(label)} (estimated from GPS)`
      : `Bus reported on route ${escapeHtml(label)}${unconfirmed ? ' (route unconfirmed)' : ''}`;
    const aria = `${route}, ${STATE_TEXT[freshness] ?? 'position'}${selected ? ', selected' : ''}`;
    icon = L.divIcon({
      className: 'pin',
      html: `<span class="${cls}" role="img" aria-label="${aria}">${escapeHtml(text)}</span>`,
      iconSize: [width, height],
      iconAnchor: [width / 2, height / 2],
    });
    cache.set(key, icon);
  }
  return icon;
}

const ADMIN_STATE_TEXT = { MOVING: 'moving', SLOW: 'slow or possibly stuck', STALE: 'last position over 2 minutes old' };

/**
 * Admin live-map bus: `status` from utils/admin liveStatus(). Moving is filled, slow is filled amber
 * with a "‖" mark, stale is a dashed outline, so the three differ in shape as well as colour.
 */
export function adminBusPin(routeLabel, status = 'MOVING', selected = false) {
  const label = String(routeLabel ?? '?');
  const key = `admin|${label}|${status}|${selected}`;
  let icon = cache.get(key);
  if (!icon) {
    const text = status === 'SLOW' ? `‖ ${label}` : label;
    const width = Math.max(34, text.length * (selected ? 9 : 8) + (selected ? 24 : 18));
    const height = selected ? 30 : 24;
    const cls = `pin-bus pin-admin-${status.toLowerCase()}${selected ? ' pin-bus-selected' : ''}`;
    const aria = `Bus on route ${escapeHtml(label)}, ${ADMIN_STATE_TEXT[status] ?? 'position'}${selected ? ', selected' : ''}`;
    icon = L.divIcon({
      className: 'pin',
      html: `<span class="${cls}" role="img" aria-label="${aria}">${escapeHtml(text)}</span>`,
      iconSize: [width, height],
      iconAnchor: [width / 2, height / 2],
    });
    cache.set(key, icon);
  }
  return icon;
}

export function stopPin(name, selected = false) {
  const key = `stop|${name}|${selected}`;
  let icon = cache.get(key);
  if (!icon) {
    const size = selected ? 22 : 14;
    icon = L.divIcon({
      className: 'pin',
      html: `<span class="pin-stop${selected ? ' pin-stop-selected' : ''}" role="img" aria-label="Bus stop: ${escapeHtml(name || 'unnamed')}${selected ? ', selected' : ''}"></span>`,
      iconSize: [size, size],
      iconAnchor: [size / 2, size / 2],
    });
    cache.set(key, icon);
  }
  return icon;
}

export const mePin = L.divIcon({
  className: 'pin',
  html: '<span class="pin-me" role="img" aria-label="Your location"></span>',
  iconSize: [18, 18],
  iconAnchor: [9, 9],
});

export function endPin(letter, label) {
  return L.divIcon({
    className: 'pin',
    html: `<span class="pin-end" role="img" aria-label="${escapeHtml(label)}">${escapeHtml(letter)}</span>`,
    iconSize: [28, 28],
    iconAnchor: [14, 14],
  });
}
