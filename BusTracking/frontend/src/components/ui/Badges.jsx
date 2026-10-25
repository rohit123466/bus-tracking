import Icon from './Icon';
import {
  busFreshness, etaStatus, ETA_SOURCE_TEXT, likelyRouteExplanation, likelyRoutes, OFF_ROUTE_EXPLANATION, routeList,
  routeUnconfirmed, shownRoute,
} from '../../utils/liveMap';
import { ageText, clockText } from '../../utils/format';
import { toggleRoute, toggleStop, favouriteRoutes, favouriteStops } from '../../store/favourites';
import { useStore } from '../../hooks/useStores';

/** Live GPS (≤30 s), Delayed GPS (30–120 s) or No live position; always spelled out, never colour only. */
export function FreshnessBadge({ epochSeconds, now }) {
  const freshness = busFreshness({ lastUpdatedEpochSeconds: epochSeconds }, now);
  const text = { LIVE: 'Live GPS', DELAYED: 'Delayed GPS', STALE: 'No live position' }[freshness];
  const tone = { LIVE: 'badge-live', DELAYED: 'badge-delayed', STALE: '' }[freshness];
  return (
    <span className={`badge ${tone}`} title={epochSeconds ? `GPS fix at ${clockText(epochSeconds)} (${ageText(epochSeconds, now)})` : 'No GPS timestamp'}>
      <span className={`dot${freshness === 'LIVE' ? ' dot-pulse' : ''}`} aria-hidden="true" />
      {text}
    </span>
  );
}

/**
 * Minutes until arrival with how they were obtained. Only a plausible number backed by a GPS fix
 * under 2 minutes old is shown as minutes; estimates are prefixed with "≈" and labelled,
 * TripUpdates predictions are not.
 */
export function Eta({ upcoming, showSource = false, now = Date.now() / 1000 }) {
  const status = etaStatus(upcoming, now);
  if (status === 'stale') {
    return (
      <span className="stack" style={{ gap: 0, justifyItems: 'end' }}>
        <span className="eta-none">Out of date</span>
        {showSource && <span className="small muted">GPS over 2 min old</span>}
      </span>
    );
  }
  if (status !== 'ok') {
    return <span className="eta-none">No estimate</span>;
  }
  const m = upcoming.etaMinutes;
  const exact = upcoming.availability === 'TRIP_UPDATE';
  const minutes = Math.max(0, Math.round(m));
  const source = ETA_SOURCE_TEXT[upcoming.availability] ?? 'Estimate';
  return (
    <span className="stack" style={{ gap: 0, justifyItems: 'end' }}>
      <span className="eta" aria-label={`${exact ? '' : 'about '}${minutes < 1 ? 'less than 1' : minutes} minute${minutes === 1 ? '' : 's'}, ${source}`}>
        {!exact && <small aria-hidden="true">≈</small>}
        <span aria-hidden="true">{minutes < 1 ? 'Now' : minutes}</span>
        {minutes >= 1 && <small aria-hidden="true">min</small>}
      </span>
      {showSource && <span className="small muted">{source}</span>}
    </span>
  );
}

/**
 * Route number chip; "?" and a dotted outline when the bus's position doesn't fit the route. Shows
 * the bus's likely route instead, still marked "?", when its drive fits one current DTC route.
 */
export function RouteChip({ upcoming }) {
  const unconfirmed = routeUnconfirmed(upcoming);
  const shown = shownRoute(upcoming, upcoming.routeNumber);
  const likely = shown !== upcoming.routeNumber;
  return (
    <span className={`route-chip${unconfirmed ? ' route-chip-unconfirmed' : ''}`}
      title={likely ? likelyRouteExplanation(upcoming, upcoming.routeNumber) : unconfirmed ? OFF_ROUTE_EXPLANATION : undefined}
      aria-label={likely ? `Likely route ${shown}, estimated from GPS; feed says ${upcoming.routeNumber}`
        : unconfirmed ? `Route ${upcoming.routeNumber}, unconfirmed` : undefined}>
      {shown}{unconfirmed && <span aria-hidden="true">?</span>}
    </span>
  );
}

/**
 * "Route unconfirmed" marker for a bus or arrival whose position doesn't fit its route number, or
 * "Likely route …" when its drive fits current DTC routes.
 */
export function RouteCheckNote({ item }) {
  if (!routeUnconfirmed(item)) return null;
  const likely = likelyRoutes(item);
  if (likely.length) return (
    <span className="badge badge-unconfirmed" title={likelyRouteExplanation(item, item.routeCode ?? item.routeNumber)}>
      <Icon name="alert" size={13} /> Likely route {routeList(likely)} (from GPS)
    </span>
  );
  return (
    <span className="badge badge-unconfirmed" title={OFF_ROUTE_EXPLANATION}>
      <Icon name="alert" size={13} /> Route unconfirmed
    </span>
  );
}

export function FavRouteButton({ routeNumber }) {
  const fav = useStore(favouriteRoutes).some(r => r.routeCode === routeNumber);
  return (
    <button type="button" className="icon-btn icon-btn-fav" aria-pressed={fav}
      aria-label={fav ? `Remove route ${routeNumber} from saved` : `Save route ${routeNumber}`}
      title={fav ? 'Saved' : 'Save route'} onClick={() => toggleRoute(routeNumber)}>
      <Icon name="star" filled={fav} />
    </button>
  );
}

export function FavStopButton({ stop }) {
  const fav = useStore(favouriteStops).some(s => s.id === stop.id);
  return (
    <button type="button" className="icon-btn icon-btn-fav" aria-pressed={fav}
      aria-label={fav ? `Remove ${stop.name} from saved stops` : `Save stop ${stop.name}`}
      title={fav ? 'Saved' : 'Save stop'} onClick={() => toggleStop(stop)}>
      <Icon name="star" filled={fav} />
    </button>
  );
}
