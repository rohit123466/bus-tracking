/**
 * useFavourites — browser-local favourites, recent searches, and saved locations.
 *
 * All data is stored in localStorage only. Nothing is sent to the backend,
 * preserving passenger privacy. Guest access is fully supported.
 *
 * Storage keys:
 *   dtc_fav_routes      — Array of { routeCode }
 *   dtc_fav_stops       — Array of { id, name, lat, lng }
 *   dtc_saved_home      — { label, lat, lng } or null
 *   dtc_saved_office    — { label, lat, lng } or null
 *   dtc_recent_searches — Array of { query, type: 'route'|'stop'|'place', timestamp }
 */
import { useState, useCallback } from 'react';

const KEYS = {
  routes:  'dtc_fav_routes',
  stops:   'dtc_fav_stops',
  home:    'dtc_saved_home',
  office:  'dtc_saved_office',
  recent:  'dtc_recent_searches',
};
const MAX_RECENT = 10;

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) ?? fallback; }
  catch { return fallback; }
}
function write(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch {}
}

export default function useFavourites() {
  const [routes,  setRoutes]  = useState(() => readJson(KEYS.routes,  []));
  const [stops,   setStops]   = useState(() => readJson(KEYS.stops,   []));
  const [home,    setHome]    = useState(() => readJson(KEYS.home,    null));
  const [office,  setOffice]  = useState(() => readJson(KEYS.office,  null));
  const [recent,  setRecent]  = useState(() => readJson(KEYS.recent,  []));

  const toggleRoute = useCallback((routeCode) => {
    setRoutes(prev => {
      const next = prev.some(r => r.routeCode === routeCode)
        ? prev.filter(r => r.routeCode !== routeCode)
        : [...prev, { routeCode }];
      write(KEYS.routes, next);
      return next;
    });
  }, []);

  const isRouteFav = (routeCode) => routes.some(r => r.routeCode === routeCode);

  const toggleStop = useCallback((stop) => {
    setStops(prev => {
      const next = prev.some(s => s.id === stop.id)
        ? prev.filter(s => s.id !== stop.id)
        : [...prev, { id: stop.id, name: stop.name, lat: stop.lat, lng: stop.lng }];
      write(KEYS.stops, next);
      return next;
    });
  }, []);

  const isStopFav = (stopId) => stops.some(s => s.id === stopId);

  const saveHome = useCallback((loc) => {
    write(KEYS.home, loc);
    setHome(loc);
  }, []);

  const saveOffice = useCallback((loc) => {
    write(KEYS.office, loc);
    setOffice(loc);
  }, []);

  const addRecent = useCallback((query, type) => {
    if (!query?.trim()) return;
    setRecent(prev => {
      const filtered = prev.filter(r => !(r.query === query && r.type === type));
      const next = [{ query, type, timestamp: Date.now() }, ...filtered].slice(0, MAX_RECENT);
      write(KEYS.recent, next);
      return next;
    });
  }, []);

  const clearRecent = useCallback(() => {
    write(KEYS.recent, []);
    setRecent([]);
  }, []);

  return {
    routes, toggleRoute, isRouteFav,
    stops,  toggleStop,  isStopFav,
    home, saveHome,
    office, saveOffice,
    recent, addRecent, clearRecent,
  };
}
