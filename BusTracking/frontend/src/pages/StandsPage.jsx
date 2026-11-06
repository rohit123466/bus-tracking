/**
 * Bus stands, two modes. "Find buses between stands" (default; ?from=&to=) lists every route
 * running from a pick-up stand to a destination stand, see components/stands/RouteFinder.
 * "Live arrivals at a stand" (?stand= or ?view=arrivals): search a stand by name and see the
 * buses heading there. Suggestions come from
 * /api/transit/stands/search (same-name stops of one stand merged); the board polls
 * /api/transit/stands/{stopId}/arrivals just after each live-feed snapshot (~every 12 s). Live GPS
 * arrivals and timetable departures are kept apart and labelled, so a scheduled time is never shown
 * as a live one. The selected stand lives in the URL (?stand=) so links share and Back works.
 */
import { useCallback, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { CircleMarker, Tooltip } from 'react-leaflet';
import BaseMap, { FitBounds, ViewTracker } from '../components/map/MapBase';
import { BusLayer, SelectedStop } from '../components/map/TransitLayers';
import Combobox from '../components/ui/Combobox';
import RouteFinder from '../components/stands/RouteFinder';
import Icon from '../components/ui/Icon';
import { Notice, SkeletonList, ErrorState, EmptyState } from '../components/ui/States';
import { Eta, FavStopButton, RouteCheckNote, RouteChip } from '../components/ui/Badges';
import usePolling from '../hooks/usePolling';
import useSearch from '../hooks/useSearch';
import { useNow, useStore } from '../hooks/useStores';
import { getStandArrivals, regionalPollDelay, searchStands } from '../api/client';
import { addRecent } from '../store/favourites';
import { themePreference } from '../store/theme';
import { themeColors } from '../utils/mapIcons';
import { ageText, clockText, plural } from '../utils/format';
import {
  DISTANCE_BASIS_TEXT, distanceKmText, EXAMPLE_QUERIES, gpsStale, locationText, mapBuses, quietRoutes,
  LIVE_ROWS, scheduledInText, SEARCH_PLACEHOLDER, standOption, standSummary, statusMeta,
} from '../utils/stands';

/** Poll fallback when the server sends no next-snapshot hint; within the 10–15 s target. */
const POLL_MS = 12000;
/** The map's first view covers the stand and this many of the soonest buses. */
const FIT_BUSES = 12;

export default function StandsPage() {
  const [params, setParams] = useSearchParams();
  const standId = params.get('stand') || '';
  // ?stand= (linked from the Explore stop panel) opens the arrivals board; everything else the
  // pick-up -> destination search.
  const mode = standId || params.get('view') === 'arrivals' ? 'arrivals' : 'routes';

  return (
    <div className="page stands-page">
      <header className="page-head">
        <h1>Bus stands</h1>
        <p>Find every DTC bus between two bus stands, or see the live buses heading to one stand.</p>
      </header>
      <div className="seg stands-modes" role="group" aria-label="What to look up">
        <button type="button" aria-pressed={mode === 'routes'} onClick={() => setParams({})}>
          Find buses between stands
        </button>
        <button type="button" aria-pressed={mode === 'arrivals'} onClick={() => setParams({ view: 'arrivals' })}>
          Live arrivals at a stand
        </button>
      </div>
      {mode === 'routes' ? <RouteFinder /> : <StandArrivals standId={standId} setParams={setParams} />}
    </div>
  );
}

/** Search one stand and see the buses heading there. */
function StandArrivals({ standId, setParams }) {
  const [text, setText] = useState('');
  const search = useSearch(text, searchStands, { minChars: 2 });
  const options = useMemo(() => search.results.map(s => standOption(s)), [search.results]);

  const open = (stand) => {
    if (!stand) return;
    addRecent(stand.name, 'stop');
    setParams({ stand: stand.id });
    setText('');
  };
  const status = search.error ? `Search failed: ${search.error.message}`
    : text.trim().length >= 2 ? `No bus stand matches “${text.trim()}”. Check the spelling or try part of the name.` : null;

  return (
    <>
      <div className="stands-search">
        <Combobox
          label="Search your bus stand"
          hideLabel
          placeholder={SEARCH_PLACEHOLDER}
          value={text}
          onInputChange={setText}
          options={options}
          loading={search.loading}
          minChars={2}
          status={status}
          autoFocus={!standId}
          onSelect={o => open(o.stand)}
          onEnter={() => open(options[0]?.stand)}
        />
        {!standId && (
          <div className="row wrap stands-examples">
            <span className="small muted">Try:</span>
            {EXAMPLE_QUERIES.map(q => (
              <button key={q} type="button" className="btn btn-sm btn-ghost" onClick={() => setText(q)}>{q}</button>
            ))}
          </div>
        )}
      </div>

      {standId ? <StandBoard key={standId} standId={standId} onClear={() => setParams({ view: 'arrivals' })} /> : (
        <EmptyState icon="pin" title="Pick a bus stand">
          Type at least two letters of a stand name. Each stand is listed once, even where it has a stop on each side of the road.
        </EmptyState>
      )}
    </>
  );
}

function StandBoard({ standId, onClear }) {
  const navigate = useNavigate();
  const now = useNow(5000);
  const q = usePolling(`stand|${standId}`, s => getStandArrivals(standId, s), {
    interval: POLL_MS, nextDelay: d => regionalPollDelay(d, POLL_MS),
  });
  const [selected, setSelected] = useState('');
  const [showAll, setShowAll] = useState(false);
  const [map, setMap] = useState(null);
  const data = q.data;

  const focusBus = useCallback((a) => {
    setSelected(a.vehicleId);
    if (!map || !Number.isFinite(a.busLatitude)) return;
    map.setView([a.busLatitude, a.busLongitude], Math.max(map.getZoom(), 15));
    map.getContainer().scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [map]);

  if (q.loading && !data) return <SkeletonList rows={5} label="Loading arrivals at this bus stand" />;
  if (q.error && !data) {
    if (q.error.kind === 'not-found') {
      return (
        <EmptyState icon="pin" title="This bus stand is not in the timetable"
          action={<button type="button" className="btn btn-sm" onClick={onClear}>Clear</button>}>
          The link may be out of date. Search for the stand by name instead.
        </EmptyState>
      );
    }
    return <ErrorState error={q.error} onRetry={q.refresh} what="arrivals at this bus stand" />;
  }

  const summary = standSummary(data, now);
  const live = data.arrivals ?? [];
  const scheduled = data.scheduled ?? [];
  const quiet = quietRoutes(data);
  const stand = { id: data.standId, name: data.stopName, lat: data.lat, lng: data.lng };
  const openRoute = r => navigate(`/?route=${encodeURIComponent(r)}`);

  return (
    <section className="stack stand-board" aria-labelledby="stand-name" style={{ gap: 16 }}>
      <div className="panel panel-pad stand-board-head">
        <div className="grow">
          <span className="section-title" style={{ margin: 0 }}>Selected bus stand</span>
          <h2 id="stand-name">{data.stopName}</h2>
          <span className="meta">
            <span>{plural(data.stops.length, 'stop')} · {plural(data.routeNumbers.length, 'route')}</span>
            <span>Stop ID {data.stops.map(s => s.id).join(', ')}</span>
          </span>
        </div>
        <div className="row wrap" style={{ gap: 8 }}>
          <FavStopButton stop={stand} />
          <Link className="btn btn-sm" to={`/?stop=${encodeURIComponent(data.standId)}`}><Icon name="map" size={16} /> Live map</Link>
        </div>
        <RefreshIndicator query={q} />
      </div>

      <div className="stand-kpis" role="group" aria-label="Summary">
        <div className="panel panel-pad stand-kpi">
          <span className="stand-kpi-label">Buses approaching</span>
          <strong className="stand-kpi-value">{summary.approaching}</strong>
          <span className="small muted">within 10 min · {plural(summary.live, 'bus', 'buses')} tracked live</span>
        </div>
        <div className="panel panel-pad stand-kpi">
          <span className="stand-kpi-label">Next arriving bus</span>
          {summary.next ? (
            <span className="row" style={{ gap: 8 }}>
              <RouteChip upcoming={summary.next} />
              <Eta upcoming={summary.next} now={now} />
            </span>
          ) : <strong className="stand-kpi-value stand-kpi-none">—</strong>}
          <span className="small muted truncate">
            {summary.next ? (summary.next.destination ? `to ${summary.next.destination}` : 'Destination not published')
              : data.summary?.nextScheduled ? `Timetable: ${data.summary.nextScheduled.routeNumber} at ${data.summary.nextScheduled.scheduledTime} (not live)`
              : 'No live bus tracked'}
          </span>
        </div>
        <div className="panel panel-pad stand-kpi">
          <span className="stand-kpi-label">Live feed</span>
          <strong className={`stand-kpi-value stand-kpi-text ${data.feedAvailable ? 'stand-kpi-ok' : 'stand-kpi-warn'}`}>
            {data.feedAvailable ? 'Receiving' : 'Interrupted'}
          </strong>
          <span className="small muted">{data.feedFetchedAt ? `Last feed update ${ageText(data.feedFetchedAt, now)}` : 'No feed update yet'}</span>
        </div>
      </div>

      {data.notice && <Notice tone="warn" role="status">{data.notice}</Notice>}
      {!data.feedAvailable && live.length > 0 && (
        <Notice tone="warn">The live feed was interrupted. Buses below are shown at their last GPS fix (under 2 min old).</Notice>
      )}
      {q.error && <Notice tone="warn">Couldn&apos;t refresh just now ({q.error.message}). Showing the last update.</Notice>}

      <div className="stand-grid">
        <div className="stand-map panel" aria-label={`Map of ${data.stopName} and buses heading there`}>
          <StandMap data={data} selected={selected} onSelect={id => setSelected(id)} setMap={setMap} now={now} />
        </div>

        <div className="stack stand-lists" style={{ gap: 16 }}>
          <section className="panel" aria-labelledby="live-h">
            <div className="panel-head">
              <h3 id="live-h">Live arrivals</h3>
              <span className="badge badge-live"><span className="dot dot-pulse" aria-hidden="true" />Live GPS</span>
            </div>
            {live.length ? (
              <>
                <ArrivalsTable arrivals={showAll ? live : live.slice(0, LIVE_ROWS)} now={now} selected={selected} onFocus={focusBus} />
                {live.length > LIVE_ROWS && (
                  <div style={{ padding: '8px 16px 12px' }}>
                    <button type="button" className="btn btn-sm" onClick={() => setShowAll(v => !v)} aria-expanded={showAll}>
                      {showAll ? `Show the first ${LIVE_ROWS}` : `Show all ${live.length} buses`}
                    </button>
                  </div>
                )}
              </>
            ) : (
              <p className="small muted" style={{ margin: 0, padding: 16 }}>
                {data.feedAvailable
                  ? 'No bus is being tracked towards this stand right now. Many buses in the live feed can’t be matched to a route or to their position along it, so a bus may still be coming.'
                  : 'The live bus feed is unavailable, so no live position can be shown.'}
              </p>
            )}
          </section>

          {scheduled.length > 0 && (
            <section className="panel" aria-labelledby="sched-h">
              <div className="panel-head">
                <h3 id="sched-h">{live.length ? 'Other routes: timetable only' : 'Scheduled arrivals'}</h3>
                <span className="badge">Scheduled · not live</span>
              </div>
              <ScheduledTable items={scheduled} now={now} />
              <p className="small muted" style={{ margin: 0, padding: '0 16px 12px' }}>
                Times from the published timetable for the next hour. No live GPS position is available for these buses.
              </p>
            </section>
          )}

          {quiet.length > 0 && (
            <details className="idle-routes panel panel-pad">
              <summary>Also stop here: {plural(quiet.length, 'route')} with no tracked bus or timetable departure in the next hour</summary>
              <div className="row wrap" style={{ marginTop: 8, gap: 6 }}>
                {quiet.map(r => (
                  <button key={r} type="button" className="route-chip route-chip-quiet" onClick={() => openRoute(r)}
                    aria-label={`Show route ${r} on the live map`}>{r}</button>
                ))}
              </div>
            </details>
          )}

          <details className="data-notes">
            <summary>How arrival times are worked out</summary>
            <p>Bus positions come from the Delhi Open Transit Data GTFS-Realtime feed. A bus is listed when its GPS position lies on a route that calls at this stand and the stand is still ahead of it. Its time comes from a live prediction where the feed has one; otherwise from how long buses on the route recently took for the same stretch, or from the distance along the route at the bus’s current speed (a typical city-bus speed of 17 km/h when it is stopped). These are estimates: traffic and dwell time change them.</p>
            <p>“Arriving” means within 300 m or 2 min; “Approaching” within 10 min; “Delayed” means the bus’s GPS fix is over 90 s old, or it is behind its timetable trip where that is known.</p>
            {data.warnings?.length > 0 && <ul>{data.warnings.map(w => <li key={w}>{w}</li>)}</ul>}
          </details>
        </div>
      </div>
    </section>
  );
}

/** When the board last refreshed, whether it is refreshing now, and a manual refresh. */
function RefreshIndicator({ query }) {
  const now = useNow(1000);
  const busy = query.loading;
  return (
    <div className="refresh-ind" role="status" aria-live="polite">
      <span className={`dot ${busy ? 'dot-pulse' : ''}`} aria-hidden="true" />
      <span className="small">
        {busy ? 'Updating…' : query.updatedAt ? `Updated ${ageText(query.updatedAt / 1000, now)}` : 'Not updated yet'}
        <span className="muted"> · refreshes every ~12 s</span>
      </span>
      <button type="button" className="btn btn-sm btn-ghost" onClick={query.refresh} disabled={busy} aria-label="Refresh arrivals now">
        <Icon name="refresh" size={16} /> Refresh
      </button>
    </div>
  );
}

function StatusBadge({ arrival }) {
  const meta = statusMeta(arrival.status);
  return <span className={`badge ${meta.tone}`} title={arrival.statusReason ?? undefined}>{meta.label}</span>;
}

/** Live arrivals: a table on wide screens, one card per bus on phones (same markup, see CSS). */
function ArrivalsTable({ arrivals, now, selected, onFocus }) {
  return (
    <table className="arr-table">
      <thead>
        <tr>
          <th scope="col">Route</th>
          <th scope="col">Destination</th>
          <th scope="col">Current location</th>
          <th scope="col">Distance</th>
          <th scope="col">Estimated arrival</th>
          <th scope="col">Status</th>
          <th scope="col">Last GPS</th>
        </tr>
      </thead>
      <tbody>
        {arrivals.map(a => {
          const stale = gpsStale(a, now);
          return (
            <tr key={a.vehicleId} aria-current={selected === a.vehicleId || undefined} className={stale ? 'is-stale' : undefined}>
              <td data-label="Route" className="c-route">
                <button type="button" className="arr-focus" onClick={() => onFocus(a)} aria-label={`Show bus ${a.vehicleId} on route ${a.routeNumber} on the map`}>
                  <RouteChip upcoming={a} />
                </button>
                <RouteCheckNote item={a} />
              </td>
              <td data-label="Destination" className="c-dest">
                {a.destination ? <>to <strong>{a.destination}</strong></> : <span className="muted">Not published</span>}
                {a.terminatesHere && <span className="badge block-badge" title="This route’s timetable ends at this stand, so the bus can’t be boarded onwards">Ends here</span>}
              </td>
              <td data-label="Current location">
                {locationText(a)}
                <span className="small muted block">{a.vehicleId}{Number.isFinite(a.speedKmh) ? ` · ${Math.round(a.speedKmh)} km/h` : ''}</span>
              </td>
              <td data-label="Distance">
                {distanceKmText(a) ?? <span className="muted">Unknown</span>}
                {a.distanceBasis && <span className="small muted block">{DISTANCE_BASIS_TEXT[a.distanceBasis]}</span>}
              </td>
              <td data-label="Estimated arrival" className="c-eta"><Eta upcoming={a} showSource now={now} /></td>
              <td data-label="Status">
                <StatusBadge arrival={a} />
                {a.statusReason && <span className="small muted block">{a.statusReason}</span>}
              </td>
              <td data-label="Last GPS">
                {clockText(a.lastGpsUpdate)}
                <span className="small muted block">{ageText(a.lastGpsUpdate, now)}</span>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function ScheduledTable({ items, now }) {
  return (
    <table className="arr-table arr-table-sched">
      <thead>
        <tr>
          <th scope="col">Route</th>
          <th scope="col">Destination</th>
          <th scope="col">Stop</th>
          <th scope="col">Scheduled</th>
          <th scope="col">Source</th>
        </tr>
      </thead>
      <tbody>
        {items.map(s => (
          <tr key={`${s.tripId}|${s.stopId}|${s.scheduledEpochSeconds}`}>
            <td data-label="Route" className="c-route"><span className="route-chip route-chip-quiet">{s.routeNumber}</span></td>
            <td data-label="Destination" className="c-dest">{s.destination ? <>to <strong>{s.destination}</strong></> : <span className="muted">Not published</span>}</td>
            <td data-label="Stop">{s.stopName}</td>
            <td data-label="Scheduled" className="c-eta"><strong>{s.scheduledTime}</strong> <span className="small muted">{scheduledInText(s, now)}</span></td>
            <td data-label="Source"><span className="badge">Timetable</span></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function StandMap({ data, selected, onSelect, setMap, now }) {
  useStore(themePreference); // re-render so canvas layers pick up the theme's colours
  const colors = themeColors();
  const [view, setView] = useState({ zoom: 15, bounds: null });
  const buses = useMemo(() => mapBuses(data.arrivals), [data.arrivals]);
  const centre = useMemo(() => ({ id: data.standId, name: data.stopName, lat: data.lat, lng: data.lng }), [data.standId, data.stopName, data.lat, data.lng]);
  // Fit to the stand and its soonest buses: the full list reaches 90 min away, i.e. half of Delhi.
  const points = useMemo(
    () => [[data.lat, data.lng], ...data.stops.map(s => [s.lat, s.lng]), ...buses.slice(0, FIT_BUSES).map(b => [b.busLatitude, b.busLongitude])],
    [data.lat, data.lng, data.stops, buses],
  );
  return (
    <>
      <div className="stand-map-canvas">
        <BaseMap ref={setMap} center={[data.lat, data.lng]} zoom={15}>
          <ViewTracker onChange={setView} />
          {data.stops.map(s => (
            <CircleMarker key={s.id} center={[s.lat, s.lng]} radius={6}
              pathOptions={{ color: colors.text, weight: 2, fillColor: colors.surface, fillOpacity: 1 }}>
              <Tooltip direction="top">{s.name} (stop {s.id})</Tooltip>
            </CircleMarker>
          ))}
          <SelectedStop stop={centre} />
          <BusLayer buses={buses} now={now} view={view} selectedId={selected} onSelect={onSelect} colors={colors} />
          {/* Once per stand: refreshes must not pull the map away from where the user panned. */}
          <FitBounds fitKey={`stand|${data.standId}`} points={points} />
        </BaseMap>
      </div>
      <div className="stand-map-legend small muted" aria-hidden="true">
        <span><i className="lg lg-live" />Live GPS</span>
        <span><i className="lg lg-delayed" />GPS 30 s–2 min old</span>
        <span><i className="lg lg-stop" />Stops of this stand</span>
      </div>
    </>
  );
}
