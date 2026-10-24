/**
 * useFavourites — favourite routes and stops, saved places and recent searches.
 *
 * Backed by the shared stores in store/favourites.js, so every page (and every open tab) sees the
 * same favourites immediately. Browser-local only; nothing is sent to the backend.
 */
import { useCallback } from 'react';
import { useStore } from './useStores';
import {
  favouriteRoutes, favouriteStops, savedHome, savedOffice, recentSearches,
  toggleRoute, toggleStop, addRecent,
} from '../store/favourites';

export default function useFavourites() {
  const routes = useStore(favouriteRoutes);
  const stops = useStore(favouriteStops);
  const home = useStore(savedHome);
  const office = useStore(savedOffice);
  const recent = useStore(recentSearches);

  const isRouteFav = useCallback(code => routes.some(r => r.routeCode === code), [routes]);
  const isStopFav = useCallback(id => stops.some(s => s.id === id), [stops]);

  return {
    routes, toggleRoute, isRouteFav,
    stops, toggleStop, isStopFav,
    home, saveHome: savedHome.set,
    office, saveOffice: savedOffice.set,
    recent, addRecent, clearRecent: () => recentSearches.set([]),
  };
}
