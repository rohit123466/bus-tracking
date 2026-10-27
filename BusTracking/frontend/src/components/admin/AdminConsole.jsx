import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import Icon from '../ui/Icon';
import { SkeletonList } from '../ui/States';
import ErrorBoundary from '../ErrorBoundary';
import ToastStack from './ToastStack';
import CommandPalette from './CommandPalette';
import { SessionTimer, SocketPill } from './AdminWidgets';
import { AdminContext, SECTIONS, sectionPath } from './adminContext';
import usePolling from '../../hooks/usePolling';
import useBusSocket from '../../hooks/useBusSocket';
import { useStore } from '../../hooks/useStores';
import { createPersistentStore } from '../../store/persistent';
import { getAdminStats, getAdminStatsHistory, getAllAdminBuses, getServiceAlerts } from '../../api/client';
import {
  eventFromFleetRow, isAuthError, liveStatus, mergeHistory, mergePositions, pushSample, routeLabel, sampleFromStats,
  STATUS_META, tokenExpiry,
} from '../../utils/admin';

const VIEWS = {
  overview: lazy(() => import('./sections/OverviewSection')),
  map: lazy(() => import('./sections/LiveMapSection')),
  routes: lazy(() => import('./sections/RoutesSection')),
  fleet: lazy(() => import('./sections/FleetSection')),
  alerts: lazy(() => import('./sections/AlertsSection')),
  occupancy: lazy(() => import('./sections/OccupancySection')),
  health: lazy(() => import('./sections/HealthSection')),
};

// Sections laid out edge to edge (the map) rather than as a padded, scrolling page.
const FILL = new Set(['map']);

const sidebarCollapsed = createPersistentStore('dtc_admin_sidebar_collapsed', false, (v) => typeof v === 'boolean');

const alertKey = (a) => `${a.type}|${a.tripId ?? ''}|${a.affectedRoute ?? ''}|${a.message}`;

export default function AdminConsole({ token, onLogout }) {
  const { section = 'overview' } = useParams();
  const navigate = useNavigate();
  const collapsed = useStore(sidebarCollapsed);
  const [paletteOpen, setPaletteOpen] = useState(false);

  // ── Toasts ──
  const [toasts, setToasts] = useState([]);
  const toastSeq = useRef(0);
  const toastTimers = useRef(new Map());
  const dismiss = useCallback((id) => {
    setToasts((list) => list.filter((t) => t.id !== id));
    clearTimeout(toastTimers.current.get(id));
    toastTimers.current.delete(id);
  }, []);
  const toast = useCallback((t) => {
    const id = ++toastSeq.current;
    const tone = t.tone ?? 'info';
    setToasts((list) => [...list.slice(-3), { ...t, tone, id }]);
    toastTimers.current.set(id, setTimeout(() => dismiss(id), tone === 'error' ? 10000 : 6000));
  }, [dismiss]);
  useEffect(() => {
    const timers = toastTimers.current;
    return () => timers.forEach(clearTimeout);
  }, []);

  const expireSession = useCallback(
    () => onLogout('Your session has ended or is no longer valid. Log in again to continue.'),
    [onLogout]);

  // ── KPIs: polled every 10 s; samples kept for sparklines, seeded from the server's history ──
  const [paused, setPaused] = useState(false);
  const statsPoll = usePolling('admin-stats', getAdminStats, { interval: 10000, enabled: !paused });
  const historyPoll = usePolling('admin-stats-history', getAdminStatsHistory, { interval: 0 });
  // The last good answer outlives a pause (usePolling forgets its data while disabled).
  const [stats, setStats] = useState({ data: null, updatedAt: null });
  const [clientSamples, setClientSamples] = useState([]);
  useEffect(() => {
    if (!statsPoll.data || !statsPoll.updatedAt) return;
    setStats({ data: statsPoll.data, updatedAt: statsPoll.updatedAt });
    setClientSamples((b) => pushSample(b, sampleFromStats(statsPoll.data, statsPoll.updatedAt)));
  }, [statsPoll.data, statsPoll.updatedAt]);
  const history = useMemo(() => mergeHistory(historyPoll.data?.samples, clientSamples), [historyPoll.data, clientSamples]);

  const statsFailing = !!statsPoll.error && !isAuthError(statsPoll.error);
  useEffect(() => {
    if (statsFailing) {
      toast({ tone: 'error', title: 'Stats refresh failed', text: `${statsPoll.error.message} Showing the last numbers received.` });
    }
    // Once per failure streak, not on every retry.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statsFailing]);

  // ── Live positions: WebSocket pushes over a fleet snapshot (re-polled on the map while the socket is down) ──
  const socket = useBusSocket();
  const pollPositions = socket.status !== 'connected' && section === 'map';
  const seed = usePolling(
    `positions:${pollPositions ? 'poll' : 'once'}`,
    (signal) => getAllAdminBuses({ status: 'MOVING,SLOW,STALE', sort: 'lastSeen,desc' }, signal, 8),
    { interval: pollPositions ? 10000 : 0, keepPrevious: true });
  const polled = useMemo(
    () => Object.fromEntries((seed.data?.rows ?? []).map((r) => [r.vehicleId, eventFromFleetRow(r)])),
    [seed.data]);
  const positions = useMemo(() => mergePositions(polled, socket.positions), [polled, socket.positions]);

  useEffect(() => {
    if (isAuthError(statsPoll.error) || isAuthError(historyPoll.error) || isAuthError(seed.error)) expireSession();
  }, [statsPoll.error, historyPoll.error, seed.error, expireSession]);

  const previousSocket = useRef(socket.status);
  useEffect(() => {
    const before = previousSocket.current;
    previousSocket.current = socket.status;
    if (before === 'connected' && socket.status === 'reconnecting') {
      toast({ tone: 'warn', title: 'Live updates interrupted', text: 'WebSocket lost. The live map polls every 10 s until it reconnects.' });
    } else if (before === 'reconnecting' && socket.status === 'connected') {
      toast({ tone: 'success', title: 'Live updates reconnected' });
    }
  }, [socket.status, toast]);

  // routeCode (GTFS route_id) → public route number, from whatever has it.
  const routeNames = useMemo(() => {
    const names = {};
    for (const r of seed.data?.rows ?? []) if (r.routeCode && r.routeName) names[r.routeCode] = r.routeName;
    for (const r of stats.data?.routeStats ?? []) if (r.routeName) names[r.routeCode] = r.routeName;
    return names;
  }, [seed.data, stats.data]);

  // ── Service alerts (public feed), shared by the Alerts section and the sidebar badge ──
  const alerts = usePolling('admin-alerts', (signal) => getServiceAlerts('', signal), { interval: 60000, keepPrevious: true });
  const seenCritical = useRef(null);
  useEffect(() => {
    const list = alerts.data?.alerts;
    if (!list) return;
    const critical = list.filter((a) => a.severity === 'CRITICAL');
    if (seenCritical.current) {
      const fresh = critical.filter((a) => !seenCritical.current.has(alertKey(a)));
      if (fresh.length) {
        toast({ tone: 'error', title: fresh.length === 1 ? 'New critical alert' : `${fresh.length} new critical alerts`, text: fresh[0].message });
      }
    }
    seenCritical.current = new Set(critical.map(alertKey));
  }, [alerts.data, toast]);
  const alertCount = (alerts.data?.alerts ?? []).filter((a) => a.severity !== 'INFO').length;

  // ── Session ──
  const expiresAt = useMemo(() => tokenExpiry(token), [token]);
  const warnSession = useCallback((left) => toast({
    tone: 'warn', title: 'Session ending soon',
    text: `You will be logged out in about ${Math.max(1, Math.round(left / 60000))} min. Save any work, then log in again.`,
  }), [toast]);
  const endSession = useCallback(() => onLogout('Your session expired. Log in again to continue.'), [onLogout]);

  // ── Command palette (Ctrl/Cmd+K) ──
  useEffect(() => {
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  const paletteItems = useMemo(() => {
    if (!paletteOpen) return [];
    const now = Date.now() / 1000;
    return [
      ...SECTIONS.map((s) => ({ id: `s:${s.id}`, label: s.label, group: 'Sections', icon: s.icon, keywords: s.keywords, to: sectionPath(s.id) })),
      ...(stats.data?.routeStats ?? []).map((r) => ({
        id: `r:${r.routeCode}`, label: `Route ${routeLabel(r)}`, group: 'Routes', icon: 'route', keywords: r.routeCode,
        sub: `${r.activeBusCount} reporting · route_id ${r.routeCode}`, to: sectionPath('map', { route: r.routeCode }),
      })),
      ...Object.values(positions).map((e) => ({
        id: `b:${e.vehicleId}`, label: e.vehicleId, group: 'Buses', icon: 'bus',
        keywords: `${e.routeId ?? ''} ${routeNames[e.routeId] ?? ''}`,
        sub: `Route ${routeNames[e.routeId] ?? e.routeId ?? 'unknown'} · ${STATUS_META[liveStatus(e, now)].label}`,
        to: sectionPath('map', { bus: e.vehicleId }),
      })),
    ];
  }, [paletteOpen, stats.data, positions, routeNames]);

  const value = useMemo(() => ({
    stats: stats.data, statsUpdatedAt: stats.updatedAt, statsError: statsPoll.error, statsLoading: statsPoll.loading,
    refreshStats: statsPoll.refresh, paused, setPaused, history,
    positions, positionsError: seed.error, positionsLoading: seed.loading && !seed.data, refreshPositions: seed.refresh,
    socket, routeNames, alerts, toast, expireSession, expiresAt,
  }), [stats, statsPoll.error, statsPoll.loading, statsPoll.refresh, paused, history, positions, seed.error, seed.loading,
    seed.data, seed.refresh, socket, routeNames, alerts, toast, expireSession, expiresAt]);

  const current = SECTIONS.find((s) => s.id === section);
  if (!current) return <Navigate to={sectionPath('overview')} replace />;
  const View = VIEWS[section];

  const navItems = (className) => SECTIONS.map((s) => (
    <li key={s.id}>
      <Link to={sectionPath(s.id)} className={className} aria-current={s.id === section ? 'page' : undefined}
        title={collapsed ? s.label : undefined}>
        <Icon name={s.icon} size={20} />
        <span className="admin-nav-label">{s.label}</span>
        {s.id === 'alerts' && alertCount > 0 && (
          <span className="admin-count" aria-label={`${alertCount} warnings or critical`}>{alertCount}</span>
        )}
      </Link>
    </li>
  ));

  return (
    <AdminContext.Provider value={value}>
      <div className={`admin${collapsed ? ' admin-collapsed' : ''}`}>
        <aside className="admin-side">
          <div className="admin-side-head">
            <span className="brand-mark"><Icon name="activity" size={18} /></span>
            <span className="admin-nav-label"><strong>Operations</strong><small>Admin console</small></span>
          </div>
          <nav aria-label="Admin sections" id="admin-nav">
            <ul className="list-reset admin-nav">{navItems('admin-nav-link')}</ul>
          </nav>
          <button type="button" className="admin-nav-link admin-collapse" aria-controls="admin-nav" aria-expanded={!collapsed}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'} onClick={() => sidebarCollapsed.set((c) => !c)}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
            <Icon name={collapsed ? 'chevronRight' : 'chevronLeft'} size={20} />
            <span className="admin-nav-label" aria-hidden="true">Collapse</span>
          </button>
        </aside>

        <div className="admin-main">
          <header className="admin-head">
            <h1>{current.label}</h1>
            <div className="admin-head-end">
              <SocketPill status={socket.status} />
              <SessionTimer expiresAt={expiresAt} onWarn={warnSession} onExpire={endSession} />
              <button type="button" className="btn btn-sm admin-search-btn" onClick={() => setPaletteOpen(true)}
                aria-label="Search sections, routes and buses" aria-keyshortcuts="Control+K Meta+K" aria-haspopup="dialog"
                title="Search (Ctrl+K / ⌘K)">
                <Icon name="search" size={16} /><span className="admin-search-text" aria-hidden="true">Search</span>
                <kbd aria-hidden="true">Ctrl K</kbd>
              </button>
              <button type="button" className="btn btn-sm btn-ghost" onClick={() => onLogout(null)} aria-label="Log out">
                <Icon name="logout" size={16} /><span className="admin-search-text" aria-hidden="true">Log out</span>
              </button>
            </div>
          </header>
          <nav className="admin-tabs" aria-label="Admin sections">
            <ul className="list-reset">{navItems('admin-tab')}</ul>
          </nav>
          <div className={`admin-body${FILL.has(section) ? ' admin-body-fill' : ''}`}>
            <ErrorBoundary resetKey={section}>
              <Suspense fallback={<div className="admin-pad"><SkeletonList rows={4} label={`Loading ${current.label}`} /></div>}>
                <View />
              </Suspense>
            </ErrorBoundary>
          </div>
        </div>
      </div>
      <ToastStack toasts={toasts} onDismiss={dismiss} />
      {paletteOpen && (
        <CommandPalette items={paletteItems} onClose={() => setPaletteOpen(false)} onPick={(item) => navigate(item.to)} />
      )}
    </AdminContext.Provider>
  );
}
