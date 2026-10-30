import { useState } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../ui/Icon';
import Combobox from '../ui/Combobox';
import { Notice, SkeletonList, ErrorState, EmptyState } from '../ui/States';
import { Eta, FavRouteButton, FreshnessBadge, RouteCheckNote, RouteChip } from '../ui/Badges';
import LocationPrompt from '../transit/LocationPrompt';
import ServiceAlertsPanel from '../ServiceAlertsPanel';
import useSearch from '../../hooks/useSearch';
import { useStore } from '../../hooks/useStores';
import { searchTransitRoutes } from '../../api/client';
import { favouriteRoutes, favouriteStops } from '../../store/favourites';
import {
  catchVerdict, etaStatus, etaText, FAR_STOP_METERS, NEAR_ROUTE_METERS, REGION_OPTIONS, routeArrivals, routeList,
  routeUnconfirmed, sortByEta, towardsAt, walkMinutesFor, withRouteArrivals,
} from '../../utils/liveMap';
import { metresBetween } from '../../utils/geo';
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
      <EmptyState icon="pin" title="No covered stops within 2 km">
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
                      <RouteChip upcoming={u} />
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
export function RoutePanel({
  route, buses, hiddenUnconfirmed = 0, busesQuery, geometryQuery, now, onSelectBus, onClose, onFit,
  gps, nearest, nearestQuery, nearStands, nearStandsQuery, liveIds, onSelectStop,
}) {
  const geometry = geometryQuery.data;
  const data = busesQuery.data;
  // Buses whose position fits the route first; the stable sort keeps the server's order otherwise.
  const listed = [...buses].sort((a, b) => routeUnconfirmed(a) - routeUnconfirmed(b));
  const unconfirmed = buses.filter(routeUnconfirmed).length;
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

      <NearbyRouteStops
        route={route} gps={gps} geometryQuery={geometryQuery} nearest={nearest} nearestQuery={nearestQuery}
        nearStands={nearStands} nearStandsQuery={nearStandsQuery} now={now}
        liveIds={liveIds} onSelectStop={onSelectStop} onSelectBus={onSelectBus}
      />

      <section aria-labelledby="rt-buses">
        <h3 className="section-title" id="rt-buses">
          Live buses {busesQuery.updatedAt ? `(${buses.length})` : ''}
        </h3>
        {busesQuery.loading && !data ? <SkeletonList rows={2} label="Loading live buses" />
          : busesQuery.error && !data ? <ErrorState error={busesQuery.error} onRetry={busesQuery.refresh} what="live buses" />
          : buses.length === 0 ? (
            <p className="small muted" style={{ margin: 0 }}>
              {hiddenUnconfirmed > 0
                ? `No bus on route ${route} whose position fits the route is inside the selected area right now.`
                : `No bus on route ${route} is reporting a live position inside the selected area right now.`}
              {data?.excludedOutsideArea > 0 && ` ${plural(data.excludedOutsideArea, 'bus', 'buses')} on this route ${data.excludedOutsideArea === 1 ? 'is' : 'are'} elsewhere in Delhi.`}
            </p>
          ) : (
            <>
              {unconfirmed > 0 && (
                <Notice tone="warn" className="mb">
                  {plural(unconfirmed, 'bus', 'buses')} reported on route {route} {unconfirmed === 1 ? 'is' : 'are'} more than
                  500 m from every stop of this route in the timetable, so {unconfirmed === 1 ? 'it' : 'they'} may not be running it
                  (depot, diversion, or an outdated route mapping in the live feed). They are marked “Route unconfirmed” and drawn hollow on the map.
                </Notice>
              )}
              <ul className="list-reset">
                {listed.map(b => (
                  <li key={b.vehicleId}>
                    <button type="button" className="row-btn" onClick={() => onSelectBus(b)}>
                      <Icon name="bus" />
                      <span className="grow">
                        <span className="truncate" style={{ display: 'block', fontWeight: 600 }}>
                          {b.nextStopName ? `Next: ${b.nextStopName}` : 'Next stop not known'}
                        </span>
                        <span className="meta">
                          <span>{b.vehicleId}</span>
                          <span>{b.direction ? `Direction ${b.direction}` : 'Direction not reported'}</span>
                          <span>GPS {ageText(b.lastUpdatedEpochSeconds, now)}</span>
                        </span>
                        <RouteCheckNote item={b} />
                      </span>
                      <FreshnessBadge epochSeconds={b.lastUpdatedEpochSeconds} now={now} />
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
        {hiddenUnconfirmed > 0 && (
          <p className="small muted">
            {plural(hiddenUnconfirmed, 'bus', 'buses')} with an unconfirmed route {hiddenUnconfirmed === 1 ? 'is' : 'are'} hidden.
            Untick “Hide buses whose route number is unconfirmed” above to show {hiddenUnconfirmed === 1 ? 'it' : 'them'}.
          </p>
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

/**
 * The route's stops within 1.5 km of the user, nearest first, each with the next buses heading to
 * it and whether each can be caught. With none that close, just the nearest stop.
 */
function NearbyRouteStops({
  route, gps, geometryQuery, nearest, nearestQuery, nearStands, nearStandsQuery, now, liveIds, onSelectStop, onSelectBus,
}) {
  const shared = { route, here: gps.location, geometry: geometryQuery.data, now, liveIds, onSelectStop, onSelectBus };
  let body;
  if (!gps.location) body = <LocationPrompt gps={gps} why="to see when this bus reaches the stops near you" />;
  else if (geometryQuery.loading && !geometryQuery.data) body = <SkeletonList rows={1} label="Finding stops near you" />;
  else if (!nearest) {
    body = (
      <p className="small muted" style={{ margin: 0 }}>
        {geometryQuery.error ? 'Couldn’t load this route’s stops, so the stops near you aren’t known.'
          : 'This route has no stops in the timetable data, so there is no nearby stop to show.'}
      </p>
    );
  } else if (nearStands.length) body = <NearStandList stands={nearStands} query={nearStandsQuery} {...shared} />;
  else {
    body = (
      <>
        <p className="small muted" style={{ margin: 0 }}>
          No stop of route {route} is within {distanceText(NEAR_ROUTE_METERS)} of you. The closest one:
        </p>
        <NearestStopBody stop={nearest.stop} query={nearestQuery} {...shared} />
      </>
    );
  }
  return (
    <section aria-labelledby="rt-nearest">
      <h3 className="section-title" id="rt-nearest">Stops near you on this route</h3>
      {body}
    </section>
  );
}

const VERDICT = {
  make: { tone: 'badge-live', text: 'You can make it' },
  'leave-now': { tone: 'badge-delayed', text: 'Leave now' },
  miss: { tone: 'badge-danger', text: 'You’ll likely miss this one' },
};

const WALK_NOTE = 'Bus times are the live estimates for each stop; your walk is a straight-line estimate at about 4.8 km/h, so allow extra time.';
const FEED_DOWN_NOTE = 'The live feed was interrupted. Times shown use each bus’s last GPS fix and may be out of date.';
const NOT_COVERED_NOTE = 'Live arrival times aren’t available for this stop: estimates cover stops in North and North-West Delhi only.';

/** Stands within 1.5 km from one route-narrowed request; the nearest is open, the rest expand. */
function NearStandList({ route, here, geometry, stands, query, now, liveIds, onSelectStop, onSelectBus }) {
  if (query.loading && !query.data) return <SkeletonList rows={3} label="Loading buses heading to stops near you" />;
  if (query.error && !query.data) return <ErrorState error={query.error} onRetry={query.refresh} what="arrivals at stops near you" />;
  const live = withRouteArrivals(stands, query.data.stops, route, now);
  return (
    <div className="stack" style={{ gap: 8 }}>
      <p className="small muted" style={{ margin: 0 }}>
        {plural(live.length, 'stop')} within {distanceText(NEAR_ROUTE_METERS)}, nearest first. Open one to see its buses.
      </p>
      {query.data.feedAvailable === false && <Notice tone="warn">{FEED_DOWN_NOTE}</Notice>}
      <ul className="list-reset stack" style={{ gap: 4 }}>
        {live.map((stand, i) => (
          <li key={stand.id}>
            <NearStand stand={stand} open={i === 0} route={route} here={here} geometry={geometry} now={now}
              liveIds={liveIds} onSelectStop={onSelectStop} onSelectBus={onSelectBus} />
          </li>
        ))}
      </ul>
      <p className="small muted" style={{ margin: 0 }}>{WALK_NOTE}</p>
      {query.error && <p className="small muted" style={{ margin: 0 }}>Couldn&apos;t refresh just now — showing the last update.</p>}
    </div>
  );
}

function NearStand({ stand, open, route, here, geometry, now, liveIds, onSelectStop, onSelectBus }) {
  // Measured from the live fix; the stands themselves are only re-picked when the user moves ~100 m.
  const distance = metresBetween(here, stand);
  const walk = walkMinutesFor(distance);
  const towards = towardsAt(geometry, stand.members.map(m => m.id));
  const soonest = stand.arrivals?.find(u => etaStatus(u, now) === 'ok');
  const next = stand.arrivals == null ? 'No live times'
    : soonest ? `Next ${etaText(soonest)}`
    : stand.arrivals.length ? 'Bus approaching' : 'No bus now';
  return (
    <details className="near-stand" open={open}>
      <summary className="row-btn">
        <Icon name="pin" />
        <span className="grow">
          <strong style={{ display: 'block' }}>{stand.name}</strong>
          <span className="meta">
            <span>{distanceText(distance)} · ~{walk} min walk</span>
            {towards.length > 0 && <span>Towards {routeList(towards)}</span>}
          </span>
        </span>
        <span className="small" style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{next}</span>
        <span className="near-stand-chev" aria-hidden="true"><Icon name="chevronDown" size={18} /></span>
      </summary>
      <div className="stack" style={{ gap: 8, padding: '4px 0 8px 8px' }}>
        {stand.arrivals == null ? <Notice tone="info">{NOT_COVERED_NOTE}</Notice>
          : <CatchList route={route} arrivals={stand.arrivals} walk={walk} now={now} liveIds={liveIds} onSelectBus={onSelectBus} />}
        <button type="button" className="btn btn-sm" style={{ justifySelf: 'start' }} onClick={() => onSelectStop(stand.members[0])}>
          <Icon name="walk" size={16} /> Open stop and walking directions
        </button>
      </div>
    </details>
  );
}

/** The single nearest route stop, when none is within 1.5 km; fetched on its own. */
function NearestStopBody({ route, here, geometry, stop, query, now, liveIds, onSelectStop, onSelectBus }) {
  const distance = metresBetween(here, stop);
  const walk = walkMinutesFor(distance);
  const stand = query.data?.stand;
  const towards = towardsAt(geometry, stand?.members?.map(m => m.id) ?? [stop.id]);

  return (
    <div className="stack" style={{ gap: 8 }}>
      <button type="button" className="row-btn" onClick={() => onSelectStop(stop)}>
        <Icon name="pin" />
        <span className="grow">
          <strong style={{ display: 'block' }}>{stop.name}</strong>
          <span className="meta">
            <span>{distanceText(distance)} · ~{walk} min walk (straight-line estimate)</span>
            {towards.length > 0 && <span>Towards {routeList(towards)}</span>}
          </span>
        </span>
        <Icon name="chevronRight" size={18} />
      </button>
      {distance > FAR_STOP_METERS && (
        <Notice tone="warn">
          This is the closest stop of route {route}, but it is {distanceText(distance)} away in a straight line. Another route may suit you better.
        </Notice>
      )}

      {query.loading && !query.data ? <SkeletonList rows={2} label="Loading buses heading to this stop" />
        : query.error && !query.data ? <ErrorState error={query.error} onRetry={query.refresh} what="arrivals at this stop" />
        : !stand ? <Notice tone="info">{NOT_COVERED_NOTE}</Notice>
        : (
          <>
            {query.data.feedAvailable === false && <Notice tone="warn">{FEED_DOWN_NOTE}</Notice>}
            <CatchList route={route} arrivals={routeArrivals(stand.upcoming, route, now)} walk={walk} now={now}
              liveIds={liveIds} onSelectBus={onSelectBus} />
            <p className="small muted" style={{ margin: 0 }}>{WALK_NOTE}</p>
            {query.error && <p className="small muted" style={{ margin: 0 }}>Couldn&apos;t refresh just now — showing the last update.</p>}
          </>
        )}
    </div>
  );
}

/** The next 3 buses heading to one stop, each with whether a `walk`-minute walk gets there first. */
function CatchList({ route, arrivals, walk, now, liveIds, onSelectBus }) {
  if (!arrivals.length) {
    return <p className="small muted" style={{ margin: 0 }}>No bus on route {route} is heading towards this stop right now.</p>;
  }
  const known = u => etaStatus(u, now) === 'ok';
  return (
    <ul className="list-reset arr-list">
      {arrivals.slice(0, 3).map((u, i) => {
        const verdict = known(u) ? VERDICT[catchVerdict(u.etaMinutes, walk)] : null;
        const next = known(u) && arrivals.slice(i + 1).find(known);
        const live = liveIds.has(u.vehicleId);
        const Wrapper = live ? 'button' : 'div';
        return (
          <li key={u.vehicleId}>
            <Wrapper {...(live ? { type: 'button', className: 'row-btn arr-row', onClick: () => onSelectBus(u.vehicleId) } : { className: 'arr-row' })}>
              <RouteChip upcoming={u} />
              <span className="grow">
                {/* Wraps rather than truncates: a nowrap line here widens the whole panel's grid. */}
                <span style={{ display: 'block', fontWeight: 600 }}>
                  Bus {u.vehicleId} · {known(u) ? `arrives in ${etaText(u)}` : 'approaching, time not known'}
                </span>
                <span className="meta">
                  {u.remainingStops != null && <span>{plural(u.remainingStops, 'stop')} away</span>}
                  <FreshnessBadge epochSeconds={u.lastGpsUpdate} now={now} />
                </span>
                {verdict && (
                  <span className="row wrap small" style={{ gap: 6, marginTop: 4 }}>
                    <span className={`badge ${verdict.tone}`}>{verdict.text}</span>
                    {verdict === VERDICT.miss && (
                      <span className="muted">{next ? `Next bus in ${etaText(next)}` : 'No later bus is tracked yet'}</span>
                    )}
                  </span>
                )}
              </span>
              <Eta upcoming={u} now={now} />
            </Wrapper>
          </li>
        );
      })}
    </ul>
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
