import { useState } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../ui/Icon';
import Combobox from '../ui/Combobox';
import { Notice, SkeletonList, ErrorState, EmptyState } from '../ui/States';
import { Eta, FavRouteButton, FreshnessBadge } from '../ui/Badges';
import LocationPrompt from '../transit/LocationPrompt';
import ServiceAlertsPanel from '../ServiceAlertsPanel';
import useSearch from '../../hooks/useSearch';
import { useStore } from '../../hooks/useStores';
import { searchTransitRoutes } from '../../api/client';
import { favouriteRoutes, favouriteStops } from '../../store/favourites';
import { REGION_OPTIONS, sortByEta } from '../../utils/liveMap';
import { ageText, distanceText, plural } from '../../utils/format';

export function RouteSearch({ onSelect }) {
  const [text, setText] = useState('');
  const { results, loading, error } = useSearch(text, searchTransitRoutes);
  const options = results.map(n => ({ id: n, label: `Route ${n}`, icon: 'bus' }));
  const pick = (number) => { if (number) { onSelect(number); setText(''); } };
  return (
    <Combobox
      label="Search a route number"
      hideLabel
      placeholder="Search a route, e.g. 534 or 990"
      value={text}
      onInputChange={setText}
      options={options}
      loading={loading}
      status={error ? error.message : 'No route number starts with that.'}
      onSelect={o => pick(o.id)}
      onEnter={v => pick(v.trim().toUpperCase())}
    />
  );
}

export function RegionSelect({ value, onChange }) {
  return (
    <label className="row small" style={{ gap: 8 }}>
      <span className="muted">Area</span>
      <select className="select-input" style={{ minHeight: 36, width: 'auto' }} value={value} onChange={e => onChange(e.target.value)}>
        {REGION_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label.replace(/ routes$/, '')}</option>)}
      </select>
    </label>
  );
}

/** Nothing selected: alerts, saved items, the nearest stands with their next buses. */
export function OverviewPanel({ gps, near, stands, alerts, onSelectStop, onSelectRoute }) {
  const savedRoutes = useStore(favouriteRoutes);
  const savedStops = useStore(favouriteStops);
  return (
    <div className="stack" style={{ gap: 20 }}>
      <ServiceAlertsPanel alertsResult={alerts} />

      {(savedRoutes.length > 0 || savedStops.length > 0) && (
        <section aria-labelledby="ov-saved">
          <h2 className="section-title" id="ov-saved">Saved</h2>
          <div className="row wrap" style={{ gap: 6 }}>
            {savedRoutes.map(r => (
              <button key={r.routeCode} type="button" className="route-chip" onClick={() => onSelectRoute(r.routeCode)}
                aria-label={`Show saved route ${r.routeCode}`}>{r.routeCode}</button>
            ))}
          </div>
          {savedStops.length > 0 && (
            <ul className="list-reset" style={{ marginTop: 6 }}>
              {savedStops.slice(0, 5).map(s => (
                <li key={s.id}>
                  <button type="button" className="row-btn" style={{ minHeight: 44 }} onClick={() => onSelectStop(s)}>
                    <Icon name="star" size={16} filled />
                    <span className="grow truncate">{s.name}</span>
                    <Icon name="chevronRight" size={18} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <section aria-labelledby="ov-near">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2 className="section-title" id="ov-near" style={{ margin: 0 }}>Near you</h2>
          {gps.location && <Link to="/nearby" className="btn btn-sm btn-ghost">All nearby stops</Link>}
        </div>
        <div style={{ marginTop: 8 }}>
          <LocationPrompt gps={gps} why="to see the nearest stops and their next buses" />
          {gps.location && <NearbyStands near={near} stands={stands} onSelectStop={onSelectStop} />}
        </div>
      </section>
    </div>
  );
}

function NearbyStands({ near, stands, onSelectStop }) {
  if (near.loading && !near.data) return <SkeletonList rows={3} label="Loading nearby stops" />;
  if (near.error && !near.data) return <ErrorState error={near.error} onRetry={near.refresh} what="nearby stops" />;
  if (!stands.length) {
    return (
      <EmptyState icon="pin" title="No covered stops within 800 m">
        Live arrivals cover stops in North and North-West Delhi. Search a route above, or open Nearby for a wider radius.
      </EmptyState>
    );
  }
  return (
    <ul className="list-reset stack" style={{ gap: 4 }}>
      {stands.map(s => {
        const next = sortByEta(s.upcoming).filter(u => u.etaMinutes != null).slice(0, 3);
        return (
          <li key={s.id}>
            <button type="button" className="row-btn" onClick={() => onSelectStop(s)}>
              <span className="grow">
                <strong className="truncate" style={{ display: 'block' }}>{s.name}</strong>
                <span className="meta"><span>{distanceText(s.distanceMeters)} · ~{Math.max(1, Math.round(s.walkMinutes))} min walk</span></span>
                <span className="row wrap" style={{ gap: 10, marginTop: 6 }}>
                  {next.length ? next.map(u => (
                    <span key={u.vehicleId} className="row" style={{ gap: 6 }}>
                      <span className="route-chip">{u.routeNumber}</span>
                      <Eta upcoming={u} />
                    </span>
                  )) : <span className="small muted">No bus tracked towards this stop now</span>}
                </span>
              </span>
              <Icon name="chevronRight" size={18} />
            </button>
          </li>
        );
      })}
    </ul>
  );
}

/** A route is selected: its live buses, directions and data caveats. */
export function RoutePanel({ route, buses, busesQuery, geometryQuery, now, onSelectBus, onClose, onFit }) {
  const geometry = geometryQuery.data;
  const data = busesQuery.data;
  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="row">
        <span className="route-chip route-chip-lg">{route}</span>
        <h2 className="grow" style={{ fontSize: '1.15rem' }}>Route {route}</h2>
        <FavRouteButton routeNumber={route} />
        {onFit && (
          <button type="button" className="icon-btn" onClick={onFit} aria-label="Fit the map to this route" title="Fit to route">
            <Icon name="fit" />
          </button>
        )}
        <button type="button" className="icon-btn" onClick={onClose} aria-label={`Close route ${route}`} title="Close">
          <Icon name="x" />
        </button>
      </div>

      <section aria-labelledby="rt-buses">
        <h3 className="section-title" id="rt-buses">
          Live buses {busesQuery.updatedAt ? `(${buses.length})` : ''}
        </h3>
        {busesQuery.loading && !data ? <SkeletonList rows={2} label="Loading live buses" />
          : busesQuery.error && !data ? <ErrorState error={busesQuery.error} onRetry={busesQuery.refresh} what="live buses" />
          : buses.length === 0 ? (
            <p className="small muted" style={{ margin: 0 }}>
              No bus on route {route} is reporting a live position inside the selected area right now.
              {data?.excludedOutsideArea > 0 && ` ${plural(data.excludedOutsideArea, 'bus', 'buses')} on this route ${data.excludedOutsideArea === 1 ? 'is' : 'are'} elsewhere in Delhi.`}
            </p>
          ) : (
            <ul className="list-reset">
              {buses.map(b => (
                <li key={b.vehicleId}>
                  <button type="button" className="row-btn" onClick={() => onSelectBus(b)}>
                    <Icon name="bus" />
                    <span className="grow">
                      <span className="truncate" style={{ display: 'block', fontWeight: 600 }}>
                        {b.nextStopName ? `Next: ${b.nextStopName}` : 'Next stop not known'}
                      </span>
                      <span className="meta">
                        <span>{b.direction ? `Direction ${b.direction}` : 'Direction not reported'}</span>
                        <span>GPS {ageText(b.lastUpdatedEpochSeconds, now)}</span>
                      </span>
                    </span>
                    <FreshnessBadge epochSeconds={b.lastUpdatedEpochSeconds} now={now} />
                  </button>
                </li>
              ))}
            </ul>
          )}
        {data?.excludedOutsideArea > 0 && buses.length > 0 && (
          <p className="small muted">{plural(data.excludedOutsideArea, 'more bus', 'more buses')} on this route {data.excludedOutsideArea === 1 ? 'is' : 'are'} outside the selected area and not shown.</p>
        )}
      </section>

      <section aria-labelledby="rt-geo">
        <h3 className="section-title" id="rt-geo">Route on the map</h3>
        <RouteGeometryNote query={geometryQuery} geometry={geometry} />
      </section>
    </div>
  );
}

function RouteGeometryNote({ query, geometry }) {
  if (query.loading && !geometry) return <SkeletonList rows={1} label="Loading route stops" />;
  if (query.error && !geometry) return <ErrorState error={query.error} onRetry={query.refresh} what="the route's stops" />;
  if (!geometry || geometry.stops.length === 0) {
    return <Notice tone="warn">This route number has no stops in the timetable data, so it can&apos;t be drawn.</Notice>;
  }
  if (geometry.source === 'STOPS_ONLY') {
    return (
      <Notice tone="info">
        Showing the {geometry.stops.length} stops this route serves. The server doesn&apos;t provide their order,
        so the path and direction aren&apos;t drawn.
      </Notice>
    );
  }
  return (
    <div className="stack" style={{ gap: 8 }}>
      <ul className="list-reset stack" style={{ gap: 6 }}>
        {geometry.patterns.map(p => (
          <li key={p.routeId} className="row small">
            <span className={`swatch swatch-${p.direction === 'DOWN' ? 1 : 0}`} aria-hidden="true" />
            <span className="grow">Towards <strong>{p.towards}</strong> <span className="muted">· {plural(p.stops.length, 'stop')}{p.direction ? ` · ${p.direction}` : ''}</span></span>
          </li>
        ))}
      </ul>
      <p className="small muted" style={{ margin: 0 }}>
        Dashed lines join the stops in timetable order (DTC GTFS, June 2024). They are not the exact road path,
        and the route may have changed since.
      </p>
    </div>
  );
}
