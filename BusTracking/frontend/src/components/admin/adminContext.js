// Shared state of the admin console (stats, live positions, alerts, toasts, session), provided by
// AdminConsole so every section reads the same polls instead of starting its own.
import { createContext, useContext, useEffect } from 'react';
import usePolling from '../../hooks/usePolling';
import { isAuthError } from '../../utils/admin';

export const AdminContext = createContext(null);

export const useAdmin = () => useContext(AdminContext);

/** usePolling for an admin endpoint: a 401/403 (expired or revoked token) logs out. */
export function useAdminPolling(key, fetcher, options) {
  const { expireSession } = useAdmin();
  const result = usePolling(key, fetcher, options);
  useEffect(() => {
    if (isAuthError(result.error)) expireSession();
  }, [result.error, expireSession]);
  return result;
}

export const SECTIONS = [
  { id: 'overview', label: 'Overview', icon: 'grid', keywords: 'kpi dashboard home stats' },
  { id: 'map', label: 'Live map', icon: 'map', keywords: 'buses positions socket' },
  { id: 'routes', label: 'Routes', icon: 'route', keywords: 'route table active' },
  { id: 'fleet', label: 'Fleet', icon: 'list', keywords: 'buses registry vehicles csv export' },
  { id: 'alerts', label: 'Alerts', icon: 'alert', keywords: 'service alerts feed cancellations' },
  { id: 'occupancy', label: 'Occupancy', icon: 'users', keywords: 'seats ticketing camera demo' },
  { id: 'health', label: 'System health', icon: 'activity', keywords: 'feed websocket actuator learning status' },
];

export const sectionPath = (id, params) => {
  const qs = params ? new URLSearchParams(params).toString() : '';
  return `/admin/${id}${qs ? `?${qs}` : ''}`;
};
