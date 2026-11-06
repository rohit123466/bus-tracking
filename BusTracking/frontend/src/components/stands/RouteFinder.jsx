/**
 * Find buses between two bus stands. The pick-up and destination fields autocomplete stands from
 * /api/transit/stands/search (debounced, previous request cancelled); "Find buses" puts both in the
 * URL (?from=&to=) and /api/transit/stands/routes lists every route whose timetable stop order
 * runs from one to the other. Routes are listed whether or not a bus is reporting GPS; live buses
 * on a route load only when its "View live buses" panel is opened.
 */
import { useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CircleMarker, Polyline, Tooltip } from 'react-leaflet';
import BaseMap, { FitBounds, ViewTracker } from '../map/MapBase';
import { BusLayer } from '../map/TransitLayers';
import Combobox from '../ui/Combobox';
import Icon from '../ui/Icon';
import { EmptyState, ErrorState, Notice, SkeletonList } from '../ui/States';
import { Eta, RouteCheckNote, RouteChip } from '../ui/Badges';
import usePolling from '../../hooks/usePolling';
import useSearch from '../../hooks/useSearch';
import { useNow, useStore } from '../../hooks/useStores';
import { findStandRoutes, getStandRouteLive, regionalPollDelay, searchStands } from '../../api/client';
import { addRecent } from '../../store/favourites';
import { themePreference } from '../../store/theme';
import { themeColors } from '../../utils/mapIcons';
import { placeParam } from '../../utils/journey';
import { ageText, distanceText, durationText, plural } from '../../utils/format';
import { locationText, standOption } from '../../utils/stands';
import {
  DEFAULT_WALK_METERS, distanceLabel, intermediateStops, liveCountText, liveMapLink, parseWalk,
  pickupStateMeta, resultsSummary, routeMapBuses, sameStand, SORTS, sortRoutes, timetableText,
  departuresText, routeSearchError, tripParams, UNSUPPORTED_TEXT, validateTrip, walkNote, withoutStand,
} from '../../utils/standRoutes';

const LIVE_POLL_MS = 12000;
/** Routes refresh their live-bus counts this often while the results are open. */
const RESULTS_POLL_MS = 60000;
/** Intermediate stops shown before "Show all". */
const STOPS_SHOWN = 4;

export default function RouteFinder() {
  const [params, setParams] = useSearchParams();
  const fromId = params.get('from') || '';
  const toId = params.get('to') || '';
  const walk = parseWalk(params.get('walk'));
  const searched = Boolean(fromId && toId);

  const [from, setFrom] = useState(null);
  const [to, setTo] = useState(null);
  const [nearby, setNearby] = useState(walk > 0);
  const [errors, setErrors] = useState({});
  const fromRef = useRef(null);
  const toRef = useRef(null);
  const resultsRef = useRef(null);

  const key = searched ? `stand-routes|${fromId}|${toId}|${walk}` : null;
  const q = usePolling(key, s => findStandRoutes({ from: fromId, to: toId, walkMeters: walk }, s), { interval: RESULTS_POLL_MS });

  // A shared link or Back: fill the fields from the answer, once per search (and only from the
  // answer for this URL, not one still showing for the previous search).
  const [filledKey, setFilledKey] = useState(null);
  const d = q.data;
  if (d && filledKey !== key && d.from?.stopIds?.includes(fromId) && d.to?.stopIds?.includes(toId)) {
    setFilledKey(key);
    setFrom(d.from);
    setTo(d.to);
  }

  const submit = (e) => {
    e.preventDefault();
    const errs = validateTrip(from, to);
    setErrors(errs);
    if (errs.from) { fromRef.current?.focus(); return; }
    if (errs.to) { toRef.current?.focus(); return; }
    addRecent(from.name, 'stop');
    addRecent(to.name, 'stop');
    const next = tripParams(from, to, nearby ? DEFAULT_WALK_METERS : 0);
    const nextKey = `stand-routes|${next.from}|${next.to}|${nearby ? DEFAULT_WALK_METERS : 0}`;
    setFilledKey(nextKey); // the fields already show what was chosen
    if (key === nextKey) q.refresh();
    else setParams(next);
    requestAnimationFrame(() => resultsRef.current?.focus({ preventScroll: false }));
  };

  const swap = () => { setFrom(to); setTo(from); setErrors({}); };

  return (
    <div className="stack" style={{ gap: 16 }}>
      <form className="panel panel-pad sr-form" onSubmit={submit} noValidate aria-label="Find buses between two bus stands">
        <div className="sr-ends">
          <div className="stack sr-fields">
            <StandPicker
              label="Select Pick-up Bus Stand" icon="pin" value={from} other={to} error={errors.from}
              placeholder="Pick-up stand, e.g. Kashmere Gate" inputRef={fromRef} autoFocus={!searched}
              onChange={s => { setFrom(s); setErrors(x => ({ ...x, from: undefined, to: s && sameStand(s, to) ? x.to : undefined })); }}
            />
            <StandPicker
              label="Select Destination Bus Stand" icon="navigation" value={to} other={from} error={errors.to}
              placeholder="Destination stand, e.g. Rohini Sector 18" inputRef={toRef}
              onChange={s => { setTo(s); setErrors(x => ({ ...x, to: undefined })); }}
            />
          </div>
          <button type="button" className="icon-btn sr-swap" onClick={swap} aria-label="Swap pick-up and destination" title="Swap">
            <Icon name="swap" />
          </button>
        </div>
        <label className="row check-row small" style={{ gap: 8 }}>
          <input type="checkbox" checked={nearby} onChange={e => setNearby(e.target.checked)} />
          <span>Include stops within a {DEFAULT_WALK_METERS} m walk of each stand <span className="muted">(big terminals have several stops)</span></span>
        </label>
        <button type="submit" className="btn btn-primary btn-block sr-submit" disabled={q.loading && !q.data}>
          <Icon name="search" size={18} /> {q.loading && !q.data ? 'Finding buses…' : 'Find Buses'}
        </button>
      </form>

      <div ref={resultsRef} tabIndex={-1} aria-live="polite" style={{ outline: 'none' }}>
        {searched ? <RouteResults q={q} onSwap={() => setParams(tripParams({ id: toId }, { id: fromId }, walk))} /> : (
          <EmptyState icon="route" title="Where are you going?">
            Choose a pick-up and a destination bus stand. Every DTC route that calls at both, in that order, is listed with the stops in between and its live buses.
          </EmptyState>
        )}
      </div>
    </div>
  );
}

/** One autocomplete field for a bus stand; `other` is the stand chosen in the other field. */
function StandPicker({ label, icon, value, other, onChange, error, placeholder, inputRef, autoFocus }) {
  const [text, setText] = useState(value?.name ?? '');
  // A stand chosen from outside (swap, shared link) shows its name; clearing keeps what was typed.
  const [shown, setShown] = useState(value);
  if (value !== shown) {
    setShown(value);
    if (value) setText(value.name);
  }
  const typing = !value || text !== value.name;
  const search = useSearch(typing ? text : '', searchStands, { minChars: 2 });
  const options = useMemo(() => withoutStand(search.results, other).map(s => standOption(s)), [search.results, other]);
  const hidOther = search.results.length > 0 && options.length === 0;
  const status = !typing ? null
    : search.error ? `Search failed: ${search.error.message}`
    : hidOther ? 'That is the stand chosen in the other field. Pick a different bus stand.'
    : text.trim().length >= 2 ? `No bus stand matches “${text.trim()}”. Check the spelling or try part of the name.` : null;
  const errorId = `${label.replace(/\W+/g, '-')}-err`;
  const sub = value ? standOption(value).sub : null;

  return (
    <div className={`sr-field${error ? ' has-error' : ''}`}>
      <Combobox
        label={label} icon={icon} placeholder={placeholder} value={text}
        onInputChange={t => { setText(t); if (value) onChange(null); }}
        options={options} loading={search.loading} minChars={2} status={status}
        autoFocus={autoFocus} inputRef={inputRef}
        onSelect={o => onChange(o.stand)}
        onEnter={() => { if (options[0]) onChange(options[0].stand); }}
      />
      {value && sub && <span className="small muted sr-field-sub"><Icon name="check" size={14} /> {sub}</span>}
      {error && <p id={errorId} className="sr-field-error" role="alert">{error}</p>}
    </div>
  );
}

function RouteResults({ q, onSwap }) {
  const [sort, setSort] = useState('best');
  const data = q.data;
  const routes = useMemo(() => sortRoutes(data?.routes, sort), [data, sort]);

  if (q.loading && !data) return <SkeletonList rows={4} label="Finding buses between these stands" />;
  if (q.error && !data) {
    const kind = routeSearchError(q.error);
    if (kind === 'unknown-stand') {
      return <EmptyState icon="pin" title="Bus stand not found">The link may be out of date. Choose the stands again.</EmptyState>;
    }
    if (kind === 'unsupported') {
      return (
        <EmptyState icon="alert" title="Route search isn't available on this server"
          action={<button type="button" className="btn btn-sm" onClick={q.refresh}><Icon name="refresh" size={16} /> Try again</button>}>
          {UNSUPPORTED_TEXT}
        </EmptyState>
      );
    }
    return <ErrorState error={q.error} onRetry={q.refresh} what="routes between these stands" />;
  }
  if (!data) return null;

  if (data.status === 'SAME_STAND') {
    return <EmptyState icon="alert" title="Same bus stand twice">Pick-up and destination must be different bus stands.</EmptyState>;
  }
  const transfers = data.transfers ?? [];
  const journey = data.from && data.to
    ? `/journey?from=${encodeURIComponent(placeParam({ lat: data.from.lat, lng: data.from.lng, label: data.from.name }))}&to=${encodeURIComponent(placeParam({ lat: data.to.lat, lng: data.to.lng, label: data.to.name }))}`
    : '/journey';
  const noDirectActions = (
    <div className="row wrap" style={{ gap: 8, justifyContent: 'center' }}>
      {data.reverseRouteCount > 0 && (
        <button type="button" className="btn btn-sm" onClick={onSwap}>
          <Icon name="swap" size={16} /> Show {plural(data.reverseRouteCount, 'route')} the other way
        </button>
      )}
      <Link className="btn btn-sm" to={journey}><Icon name="route" size={16} /> Open in the journey planner</Link>
    </div>
  );
  if (data.status !== 'OK' && !transfers.length) {
    return (
      <EmptyState icon="route" title="No direct bus found" action={noDirectActions}>
        {data.message}
        {data.status === 'NO_ROUTES' && ' No journey using 2 buses was found either.'}
        {data.walkMeters === 0 && ' Including stops within a short walk may find more routes.'}
      </EmptyState>
    );
  }

  return (
    <section className="stack" style={{ gap: 12 }} aria-labelledby="sr-results-h">
      <div className="sr-results-head">
        <div className="grow">
          <h2 id="sr-results-h" className="sr-results-title">
            {data.from.name} <Icon name="arrowRight" size={18} /> {data.to.name}
          </h2>
          <span className="small muted">{resultsSummary(data)}{!data.feedAvailable && ' · live feed unavailable'}</span>
        </div>
        {routes.length > 1 && (
          <label className="row small" style={{ gap: 6 }}>
            <span className="muted">Sort</span>
            <select className="select-input select-sm" value={sort} onChange={e => setSort(e.target.value)}>
              {SORTS.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
            </select>
          </label>
        )}
      </div>
      {q.error && <Notice tone="warn">Couldn&apos;t refresh just now ({q.error.message}). Showing the last update.</Notice>}
      {routes.length > 0 ? (
        <ol className="sr-list">
          {routes.map(r => <li key={r.key}><RouteCard route={r} feedAvailable={data.feedAvailable} fromName={data.from.name} toName={data.to.name} /></li>)}
        </ol>
      ) : (
        <div className="panel panel-pad stack sr-nodirect" style={{ gap: 8 }}>
          <strong>No direct bus</strong>
          <span className="small">{data.message} Below are journeys using 2 buses.</span>
          {noDirectActions}
        </div>
      )}
      {transfers.length > 0 && (
        <section className="stack" style={{ gap: 12 }} aria-labelledby="sr-transfers-h">
          <div>
            <h2 id="sr-transfers-h" className="sr-results-title">2 buses</h2>
            <span className="small muted">
              {plural(transfers.length, 'journey')} · quickest first · times add both rides and the walk between stops, not the wait for the second bus
            </span>
          </div>
          <ol className="sr-list">
            {transfers.map(t => <li key={t.key}><TransferCard transfer={t} feedAvailable={data.feedAvailable} fromName={data.from.name} toName={data.to.name} /></li>)}
          </ol>
        </section>
      )}
      <details className="data-notes">
        <summary>Where these routes come from</summary>
        <p>A route is listed when its timetable stop sequence calls at the pick-up stand and, later in the same sequence, at the destination stand, so routes running the other way are left out. Each direction of a route number is matched on its own. Journey times are the median scheduled time between the two stops; distances follow the route’s mapped path where it has one.</p>
        <ul>{data.notes?.map(n => <li key={n}>{n}</li>)}</ul>
      </details>
    </section>
  );
}

/** Intermediate stops as chips, the first few until "Show all". */
function StopChips({ route }) {
  const [all, setAll] = useState(false);
  const between = intermediateStops(route);
  const shown = all || between.length <= STOPS_SHOWN + 1 ? between : between.slice(0, STOPS_SHOWN);
  return (
    <div className="sr-stops-wrap">
      <span className="sr-end-label">Intermediate stops ({route.intermediateStopCount})</span>
      {between.length ? (
        <ol className="sr-stops">
          {shown.map((s, i) => <li key={`${s.id}-${i}`}>{s.name}</li>)}
          {shown.length < between.length && (
            <li className="sr-stops-more">
              <button type="button" className="link-btn" onClick={() => setAll(true)}>Show all {between.length} stops</button>
            </li>
          )}
        </ol>
      ) : <p className="small muted" style={{ margin: '4px 0 0' }}>None: the next stop is where you get off.</p>}
    </div>
  );
}

/** Live-bus count, "View Live Buses" (opens the live panel) and "View Route" for one route. */
function LiveActions({ route, feedAvailable }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <p className={`small sr-live-line ${route.liveBusCount ? '' : 'muted'}`}>{liveCountText(route, feedAvailable)}</p>
      <div className="sr-actions">
        <button type="button" className="btn btn-sm btn-primary" aria-expanded={open} onClick={() => setOpen(v => !v)}>
          <Icon name="bus" size={16} /> {open ? 'Hide live buses' : 'View Live Buses'}
        </button>
        <Link className="btn btn-sm" to={liveMapLink(route.routeNumber)}>
          <Icon name="map" size={16} /> View Route
        </Link>
      </div>
      {open && <RouteLivePanel route={route} />}
    </>
  );
}

function RouteCard({ route, feedAvailable, fromName, toName }) {
  const headingId = `sr-${route.key.replace(/\W+/g, '-')}`;
  const pickupWalk = walkNote(fromName, route.pickupWalkMeters, route.pickupAtSelectedStand);
  const destWalk = walkNote(toName, route.destinationWalkMeters, route.destinationAtSelectedStand);
  const distance = distanceLabel(route);
  const timetable = timetableText(route);

  return (
    <article className="panel sr-card" aria-labelledby={headingId}>
      <header className="sr-card-head">
        <span className="route-chip sr-chip" aria-hidden="true">{route.routeNumber}</span>
        <div className="grow" style={{ minWidth: 0 }}>
          <h3 id={headingId} className="sr-card-title">
            Route {route.routeNumber}
            {route.direction && <span className="badge sr-dir">{route.direction}</span>}
          </h3>
          <span className="small muted sr-od">{route.origin} <span aria-hidden="true">→</span><span className="vh"> to </span> {route.terminus}</span>
        </div>
        <span className={`badge ${route.liveBusCount ? 'badge-live' : ''} sr-live-badge`}>
          {route.liveBusCount > 0 && <span className="dot dot-pulse" aria-hidden="true" />}
          {route.liveBusCount > 0 ? `${route.liveBusCount} live` : 'No live bus'}
        </span>
      </header>

      <div className="sr-trip">
        <div className="sr-end">
          <span className="sr-end-label">Pick-up</span>
          <strong>{route.pickup.name}</strong>
          {pickupWalk && <span className="small sr-walk"><Icon name="walk" size={14} /> {pickupWalk}</span>}
          <span className="small muted">Stop {route.pickupStopNumber} of {route.routeStopCount}</span>
        </div>
        <div className="sr-line" aria-hidden="true">
          <span className="small">{route.scheduledMinutes != null ? durationText(route.scheduledMinutes) : '—'}</span>
          <span className="sr-line-bar" />
          <span className="small muted">{plural(route.intermediateStopCount, 'stop')} between</span>
        </div>
        <div className="sr-end sr-end-to">
          <span className="sr-end-label">Destination</span>
          <strong>{route.destination.name}</strong>
          {destWalk && <span className="small sr-walk"><Icon name="walk" size={14} /> {destWalk}</span>}
        </div>
      </div>

      <dl className="sr-facts">
        <div>
          <dt>Journey time</dt>
          <dd>{route.scheduledMinutes != null ? <>≈ {durationText(route.scheduledMinutes)} <span className="muted">(timetable)</span></> : <span className="muted">Not in the timetable</span>}</dd>
        </div>
        <div><dt>Distance</dt><dd>{distance ?? <span className="muted">Unknown</span>}</dd></div>
        <div><dt>Intermediate stops</dt><dd>{route.intermediateStopCount}</dd></div>
        <div>
          <dt>Next scheduled</dt>
          <dd>{route.nextDepartures?.length ? departuresText(route.nextDepartures) : <span className="muted">None listed</span>}</dd>
        </div>
      </dl>
      {timetable && <p className="small muted sr-tt">Timetable: {timetable}{route.variants > 1 ? ` · ${route.variants} stop variants, the most frequent shown` : ''}</p>}

      <StopChips route={route} />
      <LiveActions route={route} feedAvailable={feedAvailable} />
    </article>
  );
}

/** A journey with one change: first bus, (walk), second bus, each with its own stops and live buses. */
function TransferCard({ transfer, feedAvailable, fromName, toName }) {
  const { first, second } = transfer;
  const headingId = `sr-t-${transfer.key.replace(/\W+/g, '-')}`;
  const walk = Math.round(transfer.transferWalkMeters);
  return (
    <article className="panel sr-card sr-transfer" aria-labelledby={headingId}>
      <header className="sr-card-head">
        <span className="sr-chips" aria-hidden="true">
          <span className="route-chip sr-chip">{first.routeNumber}</span>
          <Icon name="arrowRight" size={16} />
          <span className="route-chip sr-chip">{second.routeNumber}</span>
        </span>
        <div className="grow" style={{ minWidth: 0 }}>
          <h3 id={headingId} className="sr-card-title">Route {first.routeNumber}, then {second.routeNumber}</h3>
          <span className="small muted sr-od">Change at {first.destination.name}{walk > 0 ? ` · ${walk} m walk to ${second.pickup.name}` : ''}</span>
        </div>
        <span className="sr-total">
          <strong>{transfer.totalMinutes != null ? `≈ ${durationText(transfer.totalMinutes)}` : '—'}</strong>
          <span className="small muted">{plural(transfer.totalStops, 'stop')} · excl. waiting</span>
        </span>
      </header>
      <ol className="sr-legs">
        <li><TransferLeg leg={first} n={1} feedAvailable={feedAvailable} walkFrom={walkNote(fromName, first.pickupWalkMeters, first.pickupAtSelectedStand)} /></li>
        {walk > 0 && (
          <li className="sr-leg-walk small"><Icon name="walk" size={16} /> Walk {walk} m from {first.destination.name} to {second.pickup.name}</li>
        )}
        <li><TransferLeg leg={second} n={2} feedAvailable={feedAvailable} walkTo={walkNote(toName, second.destinationWalkMeters, second.destinationAtSelectedStand)} /></li>
      </ol>
    </article>
  );
}

function TransferLeg({ leg, n, feedAvailable, walkFrom, walkTo }) {
  const distance = distanceLabel(leg);
  return (
    <div className="sr-leg">
      <div className="sr-leg-head">
        <span className="sr-end-label">{n === 1 ? '1st bus' : '2nd bus'}</span>
        <span className="route-chip">{leg.routeNumber}</span>
        {leg.direction && <span className="badge sr-dir">{leg.direction}</span>}
        <span className="small muted truncate">towards {leg.terminus}</span>
        <span className={`badge ${leg.liveBusCount ? 'badge-live' : ''} sr-live-badge`}>
          {leg.liveBusCount > 0 ? `${leg.liveBusCount} live` : 'No live bus'}
        </span>
      </div>
      <div className="sr-trip">
        <div className="sr-end">
          <span className="sr-end-label">Board at</span>
          <strong>{leg.pickup.name}</strong>
          {walkFrom && <span className="small sr-walk"><Icon name="walk" size={14} /> {walkFrom}</span>}
        </div>
        <div className="sr-line" aria-hidden="true">
          <span className="small">{leg.scheduledMinutes != null ? durationText(leg.scheduledMinutes) : '—'}</span>
          <span className="sr-line-bar" />
          <span className="small muted">{plural(leg.intermediateStopCount, 'stop')} between</span>
        </div>
        <div className="sr-end sr-end-to">
          <span className="sr-end-label">Get off at</span>
          <strong>{leg.destination.name}</strong>
          {walkTo && <span className="small sr-walk"><Icon name="walk" size={14} /> {walkTo}</span>}
        </div>
      </div>
      <p className="small muted" style={{ margin: 0 }}>
        {[distance, timetableText(leg) && `Timetable: ${timetableText(leg)}`,
          leg.nextDepartures?.length ? `next from ${leg.pickup.name}: ${departuresText(leg.nextDepartures)}` : null]
          .filter(Boolean).join(' · ')}
      </p>
      <StopChips route={leg} />
      <LiveActions route={leg} feedAvailable={feedAvailable} />
    </div>
  );
}

function RouteLivePanel({ route }) {
  const now = useNow(5000);
  const q = usePolling(`stand-route-live|${route.key}`, s => getStandRouteLive({ pickup: route.pickup.id, routeIds: route.routeIds }, s), {
    interval: LIVE_POLL_MS, nextDelay: d => regionalPollDelay(d, LIVE_POLL_MS),
  });
  const [selected, setSelected] = useState('');
  const data = q.data;

  if (q.loading && !data) return <SkeletonList rows={2} label={`Loading live buses on route ${route.routeNumber}`} />;
  if (q.error && !data) {
    if (routeSearchError(q.error) === 'unsupported') return <Notice tone="warn">{UNSUPPORTED_TEXT}</Notice>;
    return <ErrorState error={q.error} onRetry={q.refresh} what={`live buses on route ${route.routeNumber}`} />;
  }
  if (!data) return null;
  const buses = data.buses ?? [];

  return (
    <section className="sr-live" aria-label={`Live buses on route ${route.routeNumber}`}>
      {q.error && <Notice tone="warn">Couldn&apos;t refresh just now. Showing the last update.</Notice>}
      {!buses.length ? (
        <Notice tone="info" role="status">
          {data.message ?? 'No live buses currently available on this route.'}
          {route.nextDepartures?.length > 0 && ` The timetable lists the next departure from ${route.pickup.name} at ${departuresText(route.nextDepartures.slice(0, 1))} (scheduled, not live).`}
        </Notice>
      ) : (
        <>
          <p className="small" style={{ margin: 0 }}>
            <strong>{plural(buses.length, 'bus', 'buses')}</strong> reporting GPS · {data.headingToPickup} coming to {route.pickup.name}
            <span className="muted"> · updated {ageText(data.generatedAt / 1000, now)}</span>
          </p>
          <div className="sr-live-map">
            <RouteLiveMap route={route} buses={buses} selected={selected} onSelect={setSelected} now={now} />
          </div>
          <ul className="sr-buses">
            {buses.map(b => <LiveBusRow key={b.vehicleId} bus={b} pickup={route.pickup} now={now} selected={selected === b.vehicleId} onSelect={() => setSelected(b.vehicleId)} />)}
          </ul>
        </>
      )}
      <p className="small muted" style={{ margin: 0 }}>
        Buses whose feed route ID maps to this route. Arrival times are estimates from live GPS and recent trips; none is shown where it can’t be worked out.
      </p>
    </section>
  );
}

function LiveBusRow({ bus, pickup, now, selected, onSelect }) {
  const meta = pickupStateMeta(bus.pickupState);
  const coming = bus.pickupState === 'HEADING_TO_PICKUP';
  return (
    <li className="sr-bus" aria-current={selected || undefined}>
      <button type="button" className="arr-focus" onClick={onSelect} aria-label={`Show bus ${bus.vehicleId} on the map`}>
        <RouteChip upcoming={bus} />
      </button>
      <div className="grow" style={{ minWidth: 0 }}>
        <strong className="truncate">{locationText(bus)}</strong>
        <span className="small block">
          {bus.nextStopName ? <>Next stop: {bus.nextStopName}{bus.nextStopDistanceMeters != null && ` (${distanceText(bus.nextStopDistanceMeters)})`}</> : <span className="muted">Next stop unknown</span>}
        </span>
        <span className="small muted block">
          Bus {bus.vehicleId}{Number.isFinite(bus.speedKmh) ? ` · ${Math.round(bus.speedKmh)} km/h` : ''} · GPS {ageText(bus.lastGpsUpdate, now)}
        </span>
        <RouteCheckNote item={bus} />
      </div>
      <div className="sr-bus-eta">
        <span className={`badge ${meta.tone}`}>{meta.label}</span>
        {coming && (bus.etaMinutes != null
          ? <><Eta upcoming={bus} showSource now={now} /><span className="small muted">to {pickup.name}{bus.distanceToPickupMeters != null ? ` · ${distanceText(bus.distanceToPickupMeters)}` : ''}</span></>
          : <span className="small muted">No reliable arrival estimate</span>)}
        <Link className="btn btn-sm btn-ghost" to={liveMapLink(bus.routeNumber, bus.vehicleId)}>
          <Icon name="map" size={14} /> Live map
        </Link>
      </div>
    </li>
  );
}

function RouteLiveMap({ route, buses, selected, onSelect, now }) {
  useStore(themePreference); // re-render so canvas layers pick up the theme's colours
  const colors = themeColors();
  const [view, setView] = useState({ zoom: 13, bounds: null });
  const mapBuses = useMemo(() => routeMapBuses(buses), [buses]);
  const line = useMemo(() => route.stops.filter(s => Number.isFinite(s.lat)).map(s => [s.lat, s.lng]), [route.stops]);
  const points = useMemo(() => [...line, ...mapBuses.map(b => [b.busLatitude, b.busLongitude])], [line, mapBuses]);
  const ends = [route.pickup, route.destination].filter(s => Number.isFinite(s.lat));
  return (
    <BaseMap center={line[0]} zoom={13}>
      <ViewTracker onChange={setView} />
      <Polyline positions={line} pathOptions={{ color: colors.brand, weight: 4, opacity: 0.8 }} />
      {ends.map((s, i) => (
        <CircleMarker key={s.id} center={[s.lat, s.lng]} radius={7}
          pathOptions={{ color: colors.brand, weight: 3, fillColor: i === 0 ? colors.brand : colors.surface, fillOpacity: 1 }}>
          <Tooltip direction="top">{i === 0 ? 'Pick-up' : 'Destination'}: {s.name}</Tooltip>
        </CircleMarker>
      ))}
      <BusLayer buses={mapBuses} now={now} view={view} selectedId={selected} onSelect={onSelect} colors={colors} />
      <FitBounds fitKey={`sr|${route.key}`} points={points} maxZoom={15} />
    </BaseMap>
  );
}
