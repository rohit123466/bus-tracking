import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Marker, useMap } from 'react-leaflet';
import MapBase, { FitBounds } from '../../map/MapBase';
import Combobox from '../../ui/Combobox';
import Icon from '../../ui/Icon';
import { EmptyState, Notice } from '../../ui/States';
import { SocketPill, StatusBadge, Tip } from '../AdminWidgets';
import { useAdmin } from '../adminContext';
import { useNow } from '../../../hooks/useStores';
import { adminBusPin } from '../../../utils/mapIcons';
import { countStatuses, liveStatus, STATUS_META, visibleBuses } from '../../../utils/admin';
import { ageText, clockText } from '../../../utils/format';

const MAP_STATUSES = ['MOVING', 'SLOW', 'STALE'];

const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
const bearingText = (deg) => deg == null || !Number.isFinite(deg)
  ? 'Not reported'
  : `${Math.round(deg)}° (${COMPASS[Math.round(((deg % 360) + 360) % 360 / 45) % 8]})`;

const WHEELCHAIR_TEXT = { true: 'Space available', false: 'No space available' };

// One marker per bus, re-rendered only when that bus's position, label or state changes.
const BusMarker = memo(function BusMarker({ id, lat, lng, label, status, selected, onSelect }) {
  const handlers = useMemo(() => ({ click: () => onSelect(id) }), [id, onSelect]);
  return (
    <Marker position={[lat, lng]} icon={adminBusPin(label, status, selected)} eventHandlers={handlers}
      zIndexOffset={selected ? 1000 : status === 'STALE' ? -100 : 0} title={`${id}, ${STATUS_META[status].label}`} />
  );
});

/** Centres on the selected bus once when it is picked (or first appears), not on every position update. */
function FocusBus({ bus }) {
  const map = useMap();
  const target = bus ? bus.vehicleId : null;
  useEffect(() => {
    if (bus) map.setView([bus.latitude, bus.longitude], Math.max(map.getZoom(), 15));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target, map]);
  return null;
}

function BusDrawer({ id, bus, routeName, onClose }) {
  const now = useNow(1000);
  const headingRef = useRef(null);
  useEffect(() => { headingRef.current?.focus(); }, [id]);
  const status = bus ? liveStatus(bus, now) : 'NO_DATA';
  const routeQuery = routeName || bus?.routeId;
  return (
    <aside className="admin-drawer" aria-labelledby="drawer-title"
      onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); onClose(); } }}>
      <div className="panel-head">
        <h2 id="drawer-title" tabIndex={-1} ref={headingRef} className="mono">{id}</h2>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Close bus details"><Icon name="x" /></button>
      </div>
      {!bus ? (
        <EmptyState icon="bus" title="No live position">
          Nothing has been received for this vehicle since the page opened. It may be off duty or outside the covered area.
        </EmptyState>
      ) : (
        <div className="panel-pad stack">
          <div className="row wrap"><StatusBadge status={status} /></div>
          <dl className="admin-facts">
            <dt>Route</dt>
            <dd>{routeName ? <><strong>{routeName}</strong> <span className="muted mono">route_id {bus.routeId}</span></> : bus.routeId ?? 'Unknown'}</dd>
            <dt>Speed</dt>
            <dd>{bus.speedKmh == null ? 'Not reported' : `${bus.speedKmh.toFixed(1)} km/h`}</dd>
            <dt>Bearing</dt>
            <dd>{bearingText(bus.bearing)}</dd>
            <dt>Last seen</dt>
            <dd>{bus.timestamp ? <>{ageText(bus.timestamp, now)} <span className="muted">({clockText(bus.timestamp)})</span></> : 'Unknown'}</dd>
            <dt>Wheelchair space</dt>
            <dd>{WHEELCHAIR_TEXT[bus.wheelchairSpaceAvailable] ?? 'Not reported by the feed'}</dd>
            <dt>Position</dt>
            <dd className="mono">{bus.latitude.toFixed(5)}, {bus.longitude.toFixed(5)}</dd>
          </dl>
          <div className="row wrap">
            <Link className="btn btn-sm" to={`/replay?vehicle=${encodeURIComponent(id)}`}><Icon name="clock" size={16} />Journey replay</Link>
            {routeQuery && (
              <Link className="btn btn-sm" to={`/route?q=${encodeURIComponent(routeQuery)}`}><Icon name="route" size={16} />Route details</Link>
            )}
          </div>
        </div>
      )}
    </aside>
  );
}

export default function LiveMapSection() {
  const { positions, socket, routeNames, positionsError, positionsLoading, refreshPositions } = useAdmin();
  const [params, setParams] = useSearchParams();
  const route = params.get('route') || null;
  const selectedId = params.get('bus') || null;
  const [shown, setShown] = useState(() => new Set(MAP_STATUSES));
  const [routeQuery, setRouteQuery] = useState(() => (route ? routeNames[route] ?? route : ''));
  const now = useNow(5000);

  const setParam = useCallback((name, value) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    if (value) next.set(name, value); else next.delete(name);
    return next;
  }, { replace: true }), [setParams]);
  const select = useCallback((id) => setParam('bus', id), [setParam]);

  const buses = useMemo(() => visibleBuses(positions, { route, shown, now }), [positions, route, shown, now]);
  const counts = useMemo(() => countStatuses(positions, { route, now }), [positions, route, now]);
  const selected = selectedId ? positions[selectedId] : null;
  const routeTitle = route ? (routeNames[route] ? `Route ${routeNames[route]}` : `route_id ${route}`) : null;

  const routeOptions = useMemo(() => {
    const codes = new Set(Object.values(positions).map((p) => p.routeId).filter(Boolean));
    const q = routeQuery.trim().toLowerCase();
    return [...codes]
      .map((code) => ({
        id: code, icon: 'route',
        label: routeNames[code] ? `Route ${routeNames[code]}` : `route_id ${code}`,
        sub: routeNames[code] ? `route_id ${code}` : 'Not in the imported GTFS data',
      }))
      .filter((o) => !q || o.label.toLowerCase().includes(q) || o.id.toLowerCase().includes(q))
      .sort((a, b) => a.label.localeCompare(b.label, 'en', { numeric: true }))
      .slice(0, 40);
  }, [positions, routeNames, routeQuery]);

  const toggle = (status) => setShown((prev) => {
    const next = new Set(prev);
    if (next.has(status)) next.delete(status); else next.add(status);
    return next;
  });

  const fitPoints = useMemo(() => buses.map((b) => [b.latitude, b.longitude]), [buses]);
  const polling = socket.status !== 'connected';

  return (
    <div className={`admin-map${selectedId ? ' admin-map-drawer' : ''}`}>
      <div className="admin-map-canvas">
        <MapBase>
          {buses.map((b) => (
            <BusMarker key={b.vehicleId} id={b.vehicleId} lat={b.latitude} lng={b.longitude}
              label={routeNames[b.routeId] ?? b.routeId} status={b.status} selected={b.vehicleId === selectedId} onSelect={select} />
          ))}
          <FitBounds points={fitPoints} fitKey={route && fitPoints.length ? route : null} maxZoom={15} />
          <FocusBus bus={selected} />
        </MapBase>

        <div className="map-overlay">
          <div className="panel map-controls">
            <div className="row map-route">
              <div className="grow">
                <Combobox label="Filter by route" hideLabel value={routeQuery} onInputChange={setRouteQuery} options={routeOptions}
                  onSelect={(o) => { setRouteQuery(o.label); setParam('route', o.id); }}
                  placeholder="Filter by route…" icon="route" minChars={0} status="No live bus on a matching route" />
              </div>
              {route && (
                <button type="button" className="btn btn-sm" onClick={() => { setRouteQuery(''); setParam('route', null); }}
                  aria-label={`Clear route filter (${routeTitle})`}>
                  <Icon name="x" size={16} />Clear
                </button>
              )}
            </div>
            <fieldset className="status-toggles">
              <legend className="vh">Show buses by status</legend>
              {MAP_STATUSES.map((s) => (
                <button key={s} type="button" className="status-toggle" aria-pressed={shown.has(s)} onClick={() => toggle(s)}>
                  <span className={`status-toggle-pin pin-bus pin-admin-${s.toLowerCase()}`} aria-hidden="true">{s === 'SLOW' ? '‖' : ''}</span>
                  {STATUS_META[s].label}
                  <strong>{counts[s]}</strong>
                </button>
              ))}
              <Tip label="About these statuses"
                text={`Moving: ${STATUS_META.MOVING.hint} Slow: ${STATUS_META.SLOW.hint} Stale: ${STATUS_META.STALE.hint}`} />
            </fieldset>
          </div>
          <div className="map-status">
            {polling && <SocketPill status={socket.status} />}
            {polling && <span className="tag">Polling every 10 s</span>}
            <span className="tag" role="status">{buses.length} shown{routeTitle ? ` · ${routeTitle}` : ''}</span>
          </div>
          {positionsError && (
            <Notice tone="warn" role="alert" className="map-notice">
              Bus snapshot failed: {positionsError.message}{' '}
              <button type="button" className="btn btn-sm" onClick={refreshPositions}>Try again</button>
            </Notice>
          )}
          {!positionsLoading && buses.length === 0 && (
            <div className="panel map-empty">
              <EmptyState icon="bus" title="No buses to show">
                {route || shown.size < MAP_STATUSES.length
                  ? 'No bus matches the route filter and status toggles.'
                  : 'No live positions have been received. Check System health for the feed status.'}
              </EmptyState>
            </div>
          )}
        </div>
      </div>
      {selectedId && (
        <BusDrawer id={selectedId} bus={selected} routeName={selected ? routeNames[selected.routeId] : null} onClose={() => select(null)} />
      )}
    </div>
  );
}
