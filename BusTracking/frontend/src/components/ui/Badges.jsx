import Icon from './Icon';
import { busFreshness, ETA_SOURCE_TEXT } from '../../utils/liveMap';
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
 * Minutes until arrival with how they were obtained. Only a real number is shown as minutes;
 * estimates are prefixed with "≈" and labelled, TripUpdates predictions are not.
 */
export function Eta({ upcoming, showSource = false }) {
  const m = upcoming?.etaMinutes;
  if (m == null || !Number.isFinite(m)) {
    return <span className="eta-none">No estimate</span>;
  }
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
