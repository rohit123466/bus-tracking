// Favourite routes and stops, saved places and recent searches. Browser-local only: nothing is sent
// to the backend. Keys are unchanged from the earlier useFavourites hook so saved data carries over.
import { createPersistentStore } from './persistent.js';

const isArray = Array.isArray;
const isPlace = v => v === null || (typeof v === 'object' && Number.isFinite(v.lat) && Number.isFinite(v.lng));

export const favouriteRoutes = createPersistentStore('dtc_fav_routes', [], isArray);
export const favouriteStops = createPersistentStore('dtc_fav_stops', [], isArray);
export const savedHome = createPersistentStore('dtc_saved_home', null, isPlace);
export const savedOffice = createPersistentStore('dtc_saved_office', null, isPlace);
export const recentSearches = createPersistentStore('dtc_recent_searches', [], isArray);

export const MAX_RECENT = 10;

export function toggleRouteIn(list, routeCode) {
  return list.some(r => r.routeCode === routeCode)
    ? list.filter(r => r.routeCode !== routeCode)
    : [...list, { routeCode }];
}

export function toggleStopIn(list, stop) {
  return list.some(s => s.id === stop.id)
    ? list.filter(s => s.id !== stop.id)
    : [...list, { id: stop.id, name: stop.name, lat: stop.lat, lng: stop.lng }];
}

export function addRecentTo(list, query, type, now = Date.now()) {
  if (!query?.trim()) return list;
  const rest = list.filter(r => !(r.query === query && r.type === type));
  return [{ query, type, timestamp: now }, ...rest].slice(0, MAX_RECENT);
}

export const toggleRoute = routeCode => favouriteRoutes.set(list => toggleRouteIn(list, routeCode));
export const toggleStop = stop => favouriteStops.set(list => toggleStopIn(list, stop));
export const addRecent = (query, type) => recentSearches.set(list => addRecentTo(list, query, type));
