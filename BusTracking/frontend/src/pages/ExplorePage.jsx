/**
 * Explore — the live map. Buses on North / North-West Delhi routes from /api/transit/buses/regional
 * (polled once per feed update), searchable by route; a selected route is drawn from its timetable stop
 * patterns and the map fits to it. A side panel (bottom sheet on phones) shows the overview, a route,
 * a bus or a stop. Selection lives in the URL (?route=&bus=&stop=) so Back works and links share.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import BaseMap, { FitBounds, ViewTracker } from '../components/map/MapBase';
import { DELHI_CENTER, DELHI_ZOOM } from '../utils/geo';
import {
  BusLayer, RouteLayer, StopPins, SelectedStop, UserMarker, WalkLine,
} from '../components/map/TransitLayers';
import { themeColors } from '../utils/mapIcons';
import { RouteSearch, RegionSelect, OverviewPanel, RoutePanel } from '../components/explore/ExplorePanels';
import { BusPanel, StopPanel } from '../components/explore/DetailPanels';
import Icon from '../components/ui/Icon';
import usePolling from '../hooks/usePolling';
import useUserLocation from '../hooks/useUserLocation';
import useWalkingNavigation from '../hooks/useWalkingNavigation';
import { useMediaQuery, useNow, useStore, useT } from '../hooks/useStores';
import { createPersistentStore } from '../store/persistent';
import {
  getRegionalBuses, getRegionalStops, getRouteGeometry, getServiceAlerts, getStopArrivals, regionalPollDelay, searchTransitStops,
} from '../api/client';
import { visibleBuses } from '../utils/navigation';
import {
  DEFAULT_REGION, feedState, groupStands, NEAR_ROUTE_METERS, nearestRouteStop, routeStandsNear, routeUnconfirmed,
} from '../utils/liveMap';
import { addRecent } from '../store/favourites';
import { themePreference } from '../store/theme';
import { ageText, clockText, plural } from '../utils/format';

const noop = () => {};

// Remembered per browser: whether buses whose route number doesn't fit their position are hidden.
const hideUnconfirmedPref = createPersistentStore('dtc_hide_unconfirmed', false, v => typeof v === 'boolean');

function StatusPill({ state, count, unconfirmed, hidden, updatedAt, now, route }) {
  const what = route ? ` on route ${route}` : '';
  const check = hidden ? ` (${hidden} with an unconfirmed route hidden)`
    : unconfirmed ? ` (${unconfirmed} with an unconfirmed route)` : '';
  const text = {
    loading: 'Connecting to the live bus feed…',
    live: `Live feed · ${plural(count, 'bus', 'buses')}${what}${check} · updated ${ageText(updatedAt / 1000, now)}`,
    stale: `Not updating · last update ${clockText(updatedAt)}. Buses with GPS older than 2 min are hidden.`,
    // The server keeps recent positions when a feed poll fails; each is still ≤ 2 min old.
    down: count
      ? `Live feed interrupted · showing ${plural(count, 'bus', 'buses')} at their last GPS fix (under 2 min old)`
      : 'Live vehicle feed unavailable. No bus can be shown right now.',
    error: 'Can’t reach the live bus feed. Retrying…',
  }[state];
  return (
    <div className={`status-pill status-${state}`} role="status" aria-live="polite">
      <span className={`dot${state === 'live' ? ' dot-pulse' : ''}`} aria-hidden="true" />
      <span>{text}</span>
    </div>
  );
}

export default function ExplorePage() {
  const [params, setParams] = useSearchParams();
  const route = (params.get('route') || '').trim().toUpperCase();
  const busId = params.get('bus') || '';
  const stopId = params.get('stop') || '';

  const [region, setRegion] = useState(DEFAULT_REGION);
  const [stop, setStop] = useState(null);
  const [map, setMap] = useState(null);
  const [view, setView] = useState({ zoom: 12, bounds: null });
  const isDesktop = useMediaQuery('(min-width: 900px)');
  const [panelOpen, setPanelOpen] = useState(true);
  const now = useNow(5000);
  const gps = useUserLocation();
  const t = useT();
  useStore(themePreference); // re-render so canvas layers pick up the theme's colours
  const colors = themeColors();

  const select = useCallback((patch) => {
    setParams(prev => {
      const next = new URLSearchParams(prev);
      for (const [k, v] of Object.entries(patch)) {
        if (v) next.set(k, v);
        else next.delete(k);
      }
      return next;
    });
  }, [setParams]);

  // ── Data ──────────────────────────────────────────────────────────────────
  // Polls again just after the server's next feed snapshot is due (≈ every 11 s), else every 10 s.
  const busesQ = usePolling(`${region}|${route}`, s => getRegionalBuses({ region, routeNumber: route }, s), { interval: 10000, nextDelay: regionalPollDelay });
  // Re-filtered against the ticking clock: a position older than 2 min disappears even if polling stalls.
  const allBuses = useMemo(() => visibleBuses(busesQ.data?.buses ?? [], route, now), [busesQ.data, route, now]);
  const liveIds = useMemo(() => new Set(allBuses.map(b => b.vehicleId)), [allBuses]);
  // Map, route list and status pill all use this one list, so they always agree.
  const hideUnconfirmed = useStore(hideUnconfirmedPref);
  const unconfirmedCount = useMemo(() => allBuses.filter(routeUnconfirmed).length, [allBuses]);
  const buses = useMemo(
    () => (hideUnconfirmed ? allBuses.filter(b => !routeUnconfirmed(b) || b.vehicleId === busId) : allBuses),
    [allBuses, hideUnconfirmed, busId],
  );
  const feed = feedState(busesQ, now * 1000);

  const geometryQ = usePolling(route ? `geo|${route}` : null, s => getRouteGeometry(route, s), { interval: 0 });
  const geometry = geometryQ.data;

  const alertsQ = usePolling(`alerts|${route}`, s => getServiceAlerts(route, s), { interval: 60000 });

  // Rounded to ~100 m so walking around doesn't refetch on every GPS update.
  const here = gps.location;
  const nearKey = here && !route ? `${here.lat.toFixed(3)},${here.lng.toFixed(3)}|${region}` : null;
  const nearQ = usePolling(nearKey, s => getRegionalStops({ lat: here.lat, lng: here.lng, radiusMeters: 2000, limit: 60, region }, s), { interval: 30000 });
  const stands = useMemo(() => groupStands(nearQ.data?.stops).slice(0, 6), [nearQ.data]);

  // Resolve ?stop= links (the panel normally hands over the stop object directly).
  useEffect(() => {
    if (!stopId) { setStop(null); return undefined; }
    if (stop?.id === stopId) return undefined;
    const ctrl = new AbortController();
    searchTransitStops(stopId, '', ctrl.signal)
      .then(list => { const s = list.find(x => x.id === stopId); if (s) setStop(s); else select({ stop: '' }); })
      .catch(() => {});
    return () => ctrl.abort();
  }, [stopId]); // eslint-disable-line react-hooks/exhaustive-deps

  // The selected bus, or its last known record once it stops reporting.
  const liveBus = allBuses.find(b => b.vehicleId === busId) ?? null;
  const [lastBus, setLastBus] = useState(null);
  useEffect(() => { if (liveBus) setLastBus(liveBus); }, [liveBus]);
  const bus = liveBus ?? (lastBus?.vehicleId === busId ? lastBus : null);

  // The selected route's stops near the user, re-picked only when the fix moves ~100 m. Their next
  // buses are polled while the route panel shows, at about the live feed's cadence.
  const roughLat = here ? Number(here.lat.toFixed(3)) : null;
  const roughLng = here ? Number(here.lng.toFixed(3)) : null;
  const nearest = useMemo(
    () => (route && roughLat != null ? nearestRouteStop(geometry, { lat: roughLat, lng: roughLng }) : null),
    [route, geometry, roughLat, roughLng],
  );
  const nearStands = useMemo(
    () => (route && roughLat != null ? routeStandsNear(geometry, { lat: roughLat, lng: roughLng }) : []),
    [route, geometry, roughLat, roughLng],
  );
  const routePanelShown = route && !(busId && bus) && !stop;
  // One request for every stand within 1.5 km, narrowed to this route; 100 m wider so a stop right
  // at the edge isn't lost to rounding.
  const nearStandsKey = nearStands.length && routePanelShown ? `nearRoute|${route}|${roughLat},${roughLng}` : null;
  const nearStandsQ = usePolling(nearStandsKey, s => getRegionalStops({
    lat: roughLat, lng: roughLng, radiusMeters: NEAR_ROUTE_METERS + 100, limit: 60, routeNumber: route,
  }, s), { interval: 15000 });
  // No stop that close: only the nearest one, fetched on its own.
  const nearestKey = nearest && !nearStands.length && routePanelShown ? `nearest|${route}|${nearest.stop.id}` : null;
  const nearestQ = usePolling(nearestKey, s => getStopArrivals(nearest.stop, s), { interval: 15000 });

  const nav = useWalkingNavigation(noop, gps.location, stop);

  // ── Actions ───────────────────────────────────────────────────────────────
  const openPanel = () => setPanelOpen(true);
  const selectRoute = (number) => { addRecent(number, 'route'); select({ route: number, bus: '', stop: '' }); openPanel(); };
  const selectBus = (b) => { select({ bus: typeof b === 'string' ? b : b.vehicleId }); openPanel(); };
  const selectStop = (s) => { setStop(s); addRecent(s.name, 'stop'); select({ stop: s.id, bus: '' }); openPanel(); };
  const locate = async () => {
    const fix = await gps.locate();
    if (fix && map) map.setView([fix.lat, fix.lng], Math.max(map.getZoom(), 15));
  };
  // Clears the selection so the overview's "Near you" list (nearest stands and their next buses)
  // shows, and centres on the fresh fix. Without a fix the panel explains why.
  const findNearMe = async () => {
    select({ route: '', bus: '', stop: '' });
    openPanel();
    const fix = await gps.locate();
    if (fix && map) map.setView([fix.lat, fix.lng], Math.max(map.getZoom(), 15));
  };
  const resetView = () => map?.setView(DELHI_CENTER, DELHI_ZOOM);

  const routePoints = useMemo(() => [
    ...(geometry?.stops ?? []).map(s => [s.lat, s.lng]),
    ...(route ? buses.map(b => [b.busLatitude, b.busLongitude]) : []),
  ], [geometry, buses, route]);
  const fitRoute = () => routePoints.length > 1 && map?.fitBounds(routePoints, { padding: [40, 40], maxZoom: 16 });

  // ── Panel content ─────────────────────────────────────────────────────────
  let panel;
  if (busId && bus) {
    panel = (
      <BusPanel
        bus={bus} gone={!liveBus} now={now}
        geometry={route === bus.routeCode ? geometry : null}
        onBack={() => select({ bus: '' })}
        backLabel={stop ? `Back to ${stop.name}` : route ? `Back to route ${route}` : 'Back to overview'}
        onShowRoute={route === bus.routeCode ? null : () => select({ route: bus.routeCode, stop: '' })}
        onCentre={() => map?.setView([bus.busLatitude, bus.busLongitude], Math.max(map.getZoom(), 15))}
      />
    );
  } else if (stop) {
    panel = (
      <StopPanel
        stop={stop} now={now} gps={gps} nav={nav} liveIds={liveIds}
        onBack={() => { nav.stop(); select({ stop: '' }); }}
        backLabel={route ? `Back to route ${route}` : 'Back to overview'}
        onSelectBus={id => selectBus(id)}
        onSelectRoute={selectRoute}
      />
    );
  } else if (route) {
    panel = (
      <RoutePanel
        route={route} buses={buses} hiddenUnconfirmed={hideUnconfirmed ? unconfirmedCount : 0}
        busesQuery={busesQ} geometryQuery={geometryQ} now={now}
        onSelectBus={selectBus} onClose={() => select({ route: '', bus: '' })}
        onFit={routePoints.length > 1 ? fitRoute : null}
        gps={gps} nearest={nearest} nearestQuery={nearestQ} nearStands={nearStands} nearStandsQuery={nearStandsQ}
        liveIds={liveIds} onSelectStop={selectStop}
      />
    );
  } else {
    panel = (
      <OverviewPanel
        gps={gps} near={nearQ} stands={stands} alerts={alertsQ.data}
        onSelectStop={selectStop} onSelectRoute={selectRoute}
      />
    );
  }

  const sheetLabel = isDesktop ? (panelOpen ? 'Hide side panel' : 'Show side panel') : (panelOpen ? 'Collapse panel' : 'Expand panel');

  return (
    <div className={`explore ${panelOpen ? 'panel-open' : 'panel-closed'}`}>
      <aside className="side" aria-label="Explore panel">
        {!isDesktop && (
          <button type="button" className="sheet-handle" onClick={() => setPanelOpen(o => !o)}
            aria-expanded={panelOpen} aria-controls="explore-panel-body" aria-label={sheetLabel}>
            <span aria-hidden="true" />
          </button>
        )}
        <div className="side-head">
          <h1 className="vh">Explore live buses</h1>
          <RouteSearch onSelect={selectRoute} />
          <div className="row wrap" style={{ gap: 12 }}>
            {!route && <RegionSelect value={region} onChange={setRegion} />}
            <label className="row small check-row">
              <input type="checkbox" checked={hideUnconfirmed} onChange={e => hideUnconfirmedPref.set(e.target.checked)} />
              {t('map.hideUnconfirmed')}
            </label>
          </div>
        </div>
        <div className="side-body" id="explore-panel-body">
          {panel}
          {busesQ.data?.warnings?.length > 0 && !busId && !stop && (
            <details className="data-notes">
              <summary>About this data</summary>
              <p>Live positions come from the Delhi open transit data feed and are matched to the DTC timetable. Buses that can&apos;t be matched to a route, or whose GPS is older than 2 minutes, are not shown.</p>
              <ul>{busesQ.data.warnings.map(w => <li key={w}>{w}</li>)}</ul>
            </details>
          )}
        </div>
      </aside>

      <section className="map-wrap" aria-label="Map of live buses">
        <BaseMap ref={setMap}>
          <ViewTracker onChange={setView} />
          <RouteLayer geometry={route ? geometry : null} colors={colors} selectedStopId={(stop ?? nearest?.stop)?.id} onSelectStop={selectStop} />
          {!route && <StopPins stops={stands} selectedId={stop?.id} onSelect={selectStop} />}
          {/* With no stop selected, the route's stop nearest the user is highlighted instead. */}
          <SelectedStop stop={stop ?? nearest?.stop} />
          <WalkLine walk={nav.walk} from={gps.location} colors={colors} />
          <UserMarker location={gps.location} />
          <BusLayer buses={buses} now={now} view={view} selectedId={busId} onSelect={selectBus} colors={colors} />

          <FitBounds fitKey={route && (geometry || geometryQ.error) ? `route|${route}` : null} points={routePoints} />
          <FitBounds fitKey={stop ? `stop|${stop.id}` : null} points={stop ? [[stop.lat, stop.lng]] : []} />
          <FitBounds fitKey={liveBus ? `bus|${busId}` : null} points={liveBus ? [[liveBus.busLatitude, liveBus.busLongitude]] : []} />
          <FitBounds fitKey={here && !route && !stopId && !busId ? 'me' : null} points={here ? [[here.lat, here.lng]] : []} />
        </BaseMap>

        <StatusPill state={feed} count={buses.length} now={now} route={route} updatedAt={busesQ.updatedAt}
          unconfirmed={hideUnconfirmed ? 0 : unconfirmedCount} hidden={hideUnconfirmed ? unconfirmedCount : 0} />

        <div className="map-ctrls">
          {isDesktop && (
            <button type="button" className="map-btn" onClick={() => setPanelOpen(o => !o)}
              aria-expanded={panelOpen} aria-label={sheetLabel} title={sheetLabel}>
              <Icon name="panel" />
            </button>
          )}
          <button type="button" className="map-btn" onClick={locate} disabled={gps.status === 'unsupported'}
            aria-label={t('map.locate')} title={t('map.locate')}>
            <Icon name="locate" />
          </button>
          <button type="button" className="map-btn" onClick={findNearMe} disabled={gps.status === 'unsupported'}
            aria-label={t('map.nearMe')} title={t('map.nearMe')}>
            <Icon name="nearMe" />
          </button>
          {route && routePoints.length > 1 && (
            <button type="button" className="map-btn" onClick={fitRoute} aria-label={t('map.fitRoute', { route })} title={t('map.fitRoute', { route })}>
              <Icon name="fit" />
            </button>
          )}
          <button type="button" className="map-btn" onClick={resetView} aria-label={t('map.reset')} title={t('map.reset')}>
            <Icon name="resetView" />
          </button>
        </div>

        <div className="map-legend" aria-hidden="true">
          <span><i className="lg lg-live" />{t('legend.live')}</span>
          <span><i className="lg lg-delayed" />{t('legend.delayed')}</span>
          {unconfirmedCount > 0 && !hideUnconfirmed && <span><i className="lg lg-unconfirmed" />{t('legend.unconfirmed')}</span>}
          {gps.location && <span><i className="lg lg-me" />{t('legend.you')}</span>}
        </div>
      </section>
    </div>
  );
}
