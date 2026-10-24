/**
 * Saved — favourite routes and stops (with their next buses), arrival alerts, home/work places and
 * recent searches. Everything here lives in this browser's storage only.
 */
import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Icon from '../components/ui/Icon';
import { EmptyState, Notice, SkeletonList, ErrorState } from '../components/ui/States';
import { FavStopButton } from '../components/ui/Badges';
import ArrivalList from '../components/transit/ArrivalList';
import usePolling from '../hooks/usePolling';
import useUserLocation from '../hooks/useUserLocation';
import useNotificationPermission, { NOTIFICATION_TEXT } from '../hooks/useNotifications';
import { useNow, useStore } from '../hooks/useStores';
import { getStopArrivals } from '../api/client';
import {
  favouriteRoutes, favouriteStops, savedHome, savedOffice, recentSearches, toggleRoute,
} from '../store/favourites';
import { watches, removeWatch } from '../store/watches';
import { ageText } from '../utils/format';
import { placeParam } from '../utils/journey';

/** Stops whose arrivals load automatically; the rest load on demand to keep requests down. */
const AUTO_LOAD = 4;

export default function SavedPage() {
  const routes = useStore(favouriteRoutes);
  const stops = useStore(favouriteStops);
  const alerts = useStore(watches);
  const recent = useStore(recentSearches);
  const now = useNow(5000);
  const navigate = useNavigate();
  const nothing = !routes.length && !stops.length && !alerts.length;

  return (
    <div className="page">
      <header className="page-head">
        <h1>Saved</h1>
        <p>Your routes, stops and alerts. Stored only in this browser.</p>
      </header>

      {nothing && (
        <EmptyState icon="star" title="Nothing saved yet" action={<Link className="btn btn-primary" to="/">Explore the map</Link>}>
          Tap the star on a route or stop to keep it here, and set an alert to be told when a bus is close.
        </EmptyState>
      )}

      {routes.length > 0 && (
        <section className="panel panel-pad" aria-labelledby="sv-routes">
          <h2 className="section-title" id="sv-routes">Routes</h2>
          <ul className="list-reset row wrap" style={{ gap: 8 }}>
            {routes.map(r => (
              <li key={r.routeCode} className="row" style={{ gap: 0 }}>
                <button type="button" className="route-chip route-chip-lg" onClick={() => navigate(`/?route=${encodeURIComponent(r.routeCode)}`)}
                  aria-label={`Show route ${r.routeCode} on the map`}>{r.routeCode}</button>
                <button type="button" className="icon-btn" onClick={() => toggleRoute(r.routeCode)} aria-label={`Remove route ${r.routeCode}`} title="Remove">
                  <Icon name="x" size={16} />
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {stops.length > 0 && (
        <section aria-labelledby="sv-stops" className="stack">
          <h2 className="section-title" id="sv-stops" style={{ margin: 0 }}>Stops</h2>
          {stops.map((s, i) => <SavedStop key={s.id} stop={s} now={now} auto={i < AUTO_LOAD} />)}
        </section>
      )}

      <AlertsSection alerts={alerts} />
      <PlacesSection />

      {recent.length > 0 && (
        <section className="panel panel-pad" aria-labelledby="sv-recent">
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <h2 className="section-title" id="sv-recent" style={{ margin: 0 }}>Recent</h2>
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => recentSearches.set([])}>Clear</button>
          </div>
          <ul className="list-reset row wrap" style={{ gap: 6, marginTop: 8 }}>
            {recent.map(r => (
              <li key={`${r.type}-${r.query}`}>
                {r.type === 'route'
                  ? <Link className="btn btn-sm" to={`/?route=${encodeURIComponent(r.query)}`}><Icon name="bus" size={14} /> {r.query}</Link>
                  : <span className="btn btn-sm" style={{ cursor: 'default' }}><Icon name="pin" size={14} /> {r.query}</span>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function SavedStop({ stop, now, auto }) {
  const [load, setLoad] = useState(auto);
  const q = usePolling(load ? `arr|${stop.id}` : null, s => getStopArrivals(stop, s), { interval: 30000 });
  const stand = q.data?.stand;
  return (
    <article className="panel stand" aria-labelledby={`sv-${stop.id}`}>
      <div className="stand-head">
        <h3 id={`sv-${stop.id}`} className="grow truncate" style={{ fontSize: '1.05rem' }}>{stop.name}</h3>
        <Link className="icon-btn" to={`/?stop=${encodeURIComponent(stop.id)}`} aria-label={`Show ${stop.name} on the map`} title="Show on map">
          <Icon name="map" />
        </Link>
        <FavStopButton stop={stop} />
      </div>
      <div className="stand-body">
        {!load ? (
          <button type="button" className="btn btn-sm" style={{ margin: '0 8px' }} onClick={() => setLoad(true)}>Show next buses</button>
        ) : q.loading && !q.data ? <SkeletonList rows={2} label={`Loading arrivals for ${stop.name}`} />
          : q.error && !q.data ? <ErrorState error={q.error} onRetry={q.refresh} what="arrivals" />
          : !stand ? <p className="small muted" style={{ margin: '0 8px' }}>Arrival estimates cover North and North-West Delhi only.</p>
          : (
            <>
              <ArrivalList upcoming={stand.upcoming} now={now} limit={4} />
              {q.updatedAt && <p className="small muted" style={{ margin: '6px 8px 0' }}>Updated {ageText(q.updatedAt / 1000, now)}</p>}
            </>
          )}
      </div>
    </article>
  );
}

function AlertsSection({ alerts }) {
  const { permission, request } = useNotificationPermission();
  if (!alerts.length) return null;
  return (
    <section className="panel panel-pad stack" aria-labelledby="sv-alerts" style={{ gap: 10 }}>
      <h2 className="section-title" id="sv-alerts" style={{ margin: 0 }}>Arrival alerts</h2>
      <Notice tone={permission === 'granted' ? 'info' : 'warn'}>
        <p>{NOTIFICATION_TEXT[permission]}</p>
        <p>Alerts are checked only while this site is open in a tab, and use estimated times.</p>
      </Notice>
      {permission === 'default' && (
        <button type="button" className="btn btn-sm" style={{ justifySelf: 'start' }} onClick={request}>
          <Icon name="bell" size={16} /> Allow notifications
        </button>
      )}
      <ul className="list-reset stack" style={{ gap: 6 }}>
        {alerts.map(w => (
          <li key={w.id} className="row notify-row">
            <Icon name="bell" size={18} />
            <span className="grow small">
              <strong>{w.stopName}</strong> — {w.routeNumber ? `route ${w.routeNumber}` : 'any tracked bus'}, ≤ {w.thresholdMinutes} min away
            </span>
            <button type="button" className="icon-btn" onClick={() => removeWatch(w.id)} aria-label={`Delete alert for ${w.stopName}`} title="Delete">
              <Icon name="trash" size={18} />
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

function PlacesSection() {
  const home = useStore(savedHome), office = useStore(savedOffice);
  const gps = useUserLocation();
  const [message, setMessage] = useState('');

  const saveHere = async (store, label) => {
    const fix = gps.location ?? await gps.locate();
    if (!fix) { setMessage('Couldn’t get your location, so nothing was saved.'); return; }
    store.set({ label, lat: fix.lat, lng: fix.lng });
    setMessage(`${label} saved at your current location.`);
  };

  const row = (place, store, label, icon) => (
    <li className="row" style={{ minHeight: 48 }}>
      <Icon name={icon} />
      <span className="grow"><strong>{label}</strong>{!place && <span className="small muted"> — not set</span>}</span>
      {place ? (
        <>
          <Link className="btn btn-sm" to={`/journey?from=${encodeURIComponent(placeParam({ ...place, label }))}`}>From here</Link>
          <Link className="btn btn-sm" to={`/journey?to=${encodeURIComponent(placeParam({ ...place, label }))}`}>To here</Link>
          <button type="button" className="icon-btn" onClick={() => store.set(null)} aria-label={`Forget ${label}`} title="Forget"><Icon name="trash" size={18} /></button>
        </>
      ) : (
        <button type="button" className="btn btn-sm" onClick={() => saveHere(store, label)}>Use current location</button>
      )}
    </li>
  );

  return (
    <section className="panel panel-pad" aria-labelledby="sv-places">
      <h2 className="section-title" id="sv-places">Places</h2>
      <ul className="list-reset stack" style={{ gap: 4 }}>
        {row(home, savedHome, 'Home', 'home')}
        {row(office, savedOffice, 'Work', 'briefcase')}
      </ul>
      {message && <p role="status" className="small muted" style={{ marginBottom: 0 }}>{message}</p>}
    </section>
  );
}
