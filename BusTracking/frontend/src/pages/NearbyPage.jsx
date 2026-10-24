/**
 * Nearby — stops around the user with their next buses. Uses /api/transit/stops/nearby-regional
 * (the endpoint that resolves ETAs against today's feed), merges the stops of one bus stand, and
 * falls back to /stops/nearby (stops and routes only) outside the area with live estimates.
 */
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../components/ui/Icon';
import { Notice, SkeletonList, ErrorState, EmptyState } from '../components/ui/States';
import { FavStopButton } from '../components/ui/Badges';
import ArrivalList, { IdleRoutes } from '../components/transit/ArrivalList';
import NotifyControl from '../components/transit/NotifyControl';
import LocationPrompt from '../components/transit/LocationPrompt';
import usePolling from '../hooks/usePolling';
import useUserLocation from '../hooks/useUserLocation';
import { useNow } from '../hooks/useStores';
import { getRegionalStops, getNearbyGtfsStops } from '../api/client';
import { approachingBuses, filterStandsByRoute, groupStands } from '../utils/liveMap';
import { ageText, distanceText, plural } from '../utils/format';
import { Eta, RouteChip } from '../components/ui/Badges';

const RADII = [300, 500, 1000, 2000];

export default function NearbyPage() {
  const gps = useUserLocation();
  const now = useNow(5000);
  const [radius, setRadius] = useState(500);
  const [onlyTracked, setOnlyTracked] = useState(false);
  const [routeFilter, setRouteFilter] = useState('');
  const route = routeFilter.trim().toUpperCase();
  const here = gps.location;
  // Rounded to ~100 m so GPS jitter does not refetch on every position update.
  const key = here ? `${here.lat.toFixed(3)},${here.lng.toFixed(3)}|${radius}` : null;

  const q = usePolling(key, s => getRegionalStops({ lat: here.lat, lng: here.lng, radiusMeters: radius, limit: 60 }, s), {
    interval: 20000, keepPrevious: true,
  });
  const allStands = useMemo(() => groupStands(q.data?.stops), [q.data]);
  // The route filter narrows the stand list and its arrivals with the same exact-number rule as
  // the map, so "route 106" never lists 106A.
  const stands = useMemo(() => filterStandsByRoute(allStands, route), [allStands, route]);
  const soon = useMemo(() => approachingBuses(q.data?.stops)
    .filter(b => b.etaMinutes != null && (!route || String(b.routeNumber).toUpperCase() === route))
    .slice(0, 6), [q.data, route]);
  const outside = q.data && allStands.length === 0;
  // Outside the covered area: still list stops and their routes, without arrival estimates.
  const plainQ = usePolling(outside ? key : null, s => getNearbyGtfsStops(here.lat, here.lng, radius, 20, s), { interval: 0 });

  const shown = onlyTracked ? stands.filter(s => s.upcoming.length) : stands;

  return (
    <div className="page">
      <header className="page-head">
        <h1>Nearby stops</h1>
        <p>Next buses at stops around you, with how each time was estimated.</p>
      </header>

      <LocationPrompt gps={gps} why="to list the stops around you">
        <Link to="/" className="btn">Search the map instead</Link>
      </LocationPrompt>

      {here && (
        <>
          <div className="toolbar">
            <div className="seg" role="group" aria-label="Search radius">
              {RADII.map(r => (
                <button key={r} type="button" aria-pressed={radius === r} onClick={() => setRadius(r)}>{distanceText(r)}</button>
              ))}
            </div>
            <label className="row small check-row">
              <input type="checkbox" checked={onlyTracked} onChange={e => setOnlyTracked(e.target.checked)} />
              Only stops with a tracked bus
            </label>
            <label className="row small" style={{ gap: 6 }}>
              <span>Only route</span>
              <input className="select-input" style={{ width: '7rem', minHeight: 40 }} value={routeFilter}
                onChange={e => setRouteFilter(e.target.value)} placeholder="e.g. 106" inputMode="text"
                autoCapitalize="characters" spellCheck={false} aria-describedby="route-filter-hint" />
            </label>
            {route && (
              <button type="button" className="btn btn-sm btn-ghost" onClick={() => setRouteFilter('')}>
                <Icon name="x" size={16} /> Clear route
              </button>
            )}
            <span id="route-filter-hint" className="vh">Exact route number; 106 does not include 106A.</span>
            <button type="button" className="btn btn-sm" onClick={q.refresh} disabled={q.loading}>
              <Icon name="refresh" size={16} /> {q.loading ? 'Updating…' : 'Refresh'}
            </button>
            <span className="small muted" role="status">
              {q.updatedAt && `Updated ${ageText(q.updatedAt / 1000, now)}`}
              {here.accuracy > 75 && ` · Your location is accurate to about ${distanceText(here.accuracy)}, so distances are approximate.`}
            </span>
          </div>

          {q.data?.feedAvailable === false && (
            <Notice tone="warn" role="alert">The live feed was interrupted. Times shown use each bus’s last GPS fix and may be out of date.</Notice>
          )}
          {q.error && q.data && <Notice tone="warn">Couldn&apos;t refresh just now ({q.error.message}). Showing the last update.</Notice>}

          {q.loading && !q.data ? <SkeletonList rows={4} label="Loading nearby stops" />
            : q.error && !q.data ? <ErrorState error={q.error} onRetry={q.refresh} what="nearby stops" />
            : outside ? <OutsideCoverage query={plainQ} radius={radius} />
            : (
              <>
                {soon.length > 0 && (
                  <section className="panel panel-pad" aria-labelledby="soon-h">
                    <h2 className="section-title" id="soon-h">Leaving soon near you</h2>
                    <ul className="list-reset soon-list">
                      {soon.map(b => (
                        <li key={b.vehicleId} className="row" style={{ gap: 12, minHeight: 48 }}>
                          <RouteChip upcoming={b} />
                          <span className="grow">
                            <span className="truncate" style={{ display: 'block' }}>{b.stopName}</span>
                            <span className="meta"><span>GPS {ageText(b.lastGpsUpdate, now)}</span></span>
                          </span>
                          <Eta upcoming={b} now={now} />
                        </li>
                      ))}
                    </ul>
                  </section>
                )}

                {route && stands.length === 0 ? (
                  <EmptyState icon="pin" title={`No stop within ${distanceText(radius)} lists route ${route}`}>
                    The timetable has no stop served by route {route} this close to you. Try a larger radius, or check the route
                    number on the Explore map. The nearest stop to you may not serve the route you need.
                  </EmptyState>
                ) : shown.length === 0 ? (
                  <EmptyState icon="bus" title={route ? `No tracked route ${route} bus nearby` : 'No tracked buses nearby'}>
                    None of the {plural(stands.length, 'stop')} within {distanceText(radius)} has a bus{route ? ` on route ${route}` : ''} that
                    could be verified as heading towards it right now. That doesn&apos;t mean no bus is coming: many buses in the live
                    feed can&apos;t be matched to a route or to their position along it.
                  </EmptyState>
                ) : shown.map(stand => <StandCard key={stand.id} stand={stand} now={now} />)}

                <details className="data-notes">
                  <summary>About these stops and estimates</summary>
                  <p>Stop locations and the routes serving them come from DTC&apos;s published timetable (GTFS), which dates from June 2024; stops or routes may have changed since. Distances are straight-line from your location, not walking routes.</p>
                  <p>Times are estimated from live GPS positions and the timetable, or from how long recent buses on the route took. They can change with traffic and are not traffic-aware. A bus without a reliable estimate is listed without a time, and a time is withdrawn once its GPS fix is more than 2 minutes old.</p>
                  {q.data?.warnings?.length > 0 && <ul>{q.data.warnings.map(w => <li key={w}>{w}</li>)}</ul>}
                </details>
              </>
            )}
        </>
      )}
    </div>
  );
}

function StandCard({ stand, now }) {
  const [alerting, setAlerting] = useState(false);
  return (
    <article className="panel stand" aria-labelledby={`stand-${stand.id}`}>
      <div className="stand-head">
        <div className="grow">
          <h2 id={`stand-${stand.id}`} className="truncate">{stand.name}</h2>
          <span className="meta">
            <span><Icon name="walk" size={14} /> {distanceText(stand.distanceMeters)} · about {Math.max(1, Math.round(stand.walkMinutes))} min walk (straight line)</span>
            <span>Stop ID {stand.members.map(m => m.id).join(', ')}</span>
          </span>
        </div>
        <FavStopButton stop={stand} />
      </div>
      <div className="stand-body">
        <ArrivalList upcoming={stand.upcoming} now={now} limit={6} />
        <IdleRoutes stand={stand} />
        <div className="stand-actions">
          <Link className="btn btn-sm" to={`/?stop=${encodeURIComponent(stand.id)}`}><Icon name="map" size={16} /> View on map</Link>
          <button type="button" className="btn btn-sm" aria-expanded={alerting} onClick={() => setAlerting(a => !a)}>
            <Icon name="bell" size={16} /> Alerts
          </button>
        </div>
        {alerting && <div style={{ padding: '8px 8px 0' }}><NotifyControl stop={stand} routes={stand.routeNumbers} /></div>}
      </div>
    </article>
  );
}

function OutsideCoverage({ query, radius }) {
  const stops = query.data?.stops ?? [];
  return (
    <div className="stack">
      <Notice tone="info">
        Live arrival estimates cover stops in North and North-West Delhi. You appear to be outside that area,
        so the stops below show the routes that serve them, without arrival times.
      </Notice>
      {query.loading && !query.data ? <SkeletonList rows={3} label="Loading stops" />
        : query.error ? <ErrorState error={query.error} onRetry={query.refresh} what="nearby stops" />
        : stops.length === 0 ? <EmptyState icon="pin" title={`No stops within ${distanceText(radius)}`}>Try a larger radius.</EmptyState>
        : (
          <ul className="list-reset stack" style={{ gap: 8 }}>
            {stops.map(s => (
              <li key={s.id} className="panel panel-pad">
                <div className="row">
                  <strong className="grow">{s.name}</strong>
                  <FavStopButton stop={s} />
                </div>
                <span className="meta"><span>{distanceText(s.distanceMeters)} · about {Math.max(1, Math.round(s.walkMinutes))} min walk</span></span>
                <div className="row wrap" style={{ gap: 6, marginTop: 8 }}>
                  {s.routeNumbers.length ? s.routeNumbers.map(r => <span key={r} className="route-chip route-chip-quiet">{r}</span>)
                    : <span className="small muted">No routes in the timetable</span>}
                </div>
              </li>
            ))}
          </ul>
        )}
    </div>
  );
}
