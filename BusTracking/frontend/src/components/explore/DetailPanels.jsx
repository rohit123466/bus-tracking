import { Link } from 'react-router-dom';
import Icon from '../ui/Icon';
import { Notice, SkeletonList, ErrorState } from '../ui/States';
import { Eta, FavStopButton, FreshnessBadge } from '../ui/Badges';
import ArrivalList, { IdleRoutes } from '../transit/ArrivalList';
import NotifyControl from '../transit/NotifyControl';
import usePolling from '../../hooks/usePolling';
import { getRouteGeometry, getStopArrivals } from '../../api/client';
import { ageText, clockText, distanceText, durationText } from '../../utils/format';
import { placeParam } from '../../utils/journey';
import { OFF_ROUTE_EXPLANATION, ROUTE_CHECK_TEXT, routeUnconfirmed } from '../../utils/liveMap';

const NEXT_STOP_SOURCE = {
  FEED_STOP_SEQUENCE: 'reported by the feed',
  INFERRED_FROM_GPS_ON_ROUTE: 'inferred from its GPS position on the route',
  LEARNED_ROUTE_PATH: 'from the path this route’s buses were seen driving',
};

function BackHeader({ onBack, backLabel, children }) {
  return (
    <div className="row">
      <button type="button" className="icon-btn" onClick={onBack} aria-label={backLabel} title={backLabel}>
        <Icon name="chevronLeft" />
      </button>
      {children}
    </div>
  );
}

/** One bus: where it is, how fresh that is, where it is heading. */
export function BusPanel({ bus, gone, now, geometry, onBack, backLabel, onShowRoute, onCentre }) {
  const fetchGeometry = !geometry && !!bus?.routeCode;
  const geo = usePolling(fetchGeometry ? `geo|${bus.routeCode}` : null, s => getRouteGeometry(bus.routeCode, s), { interval: 0 });
  const patterns = (geometry ?? geo.data)?.patterns ?? [];
  const pattern = patterns.find(p => p.routeId === bus.routeId)
    ?? (bus.direction ? patterns.filter(p => p.direction === bus.direction) : []).find((p, _, all) => all.length === 1);

  return (
    <div className="stack" style={{ gap: 16 }}>
      <BackHeader onBack={onBack} backLabel={backLabel}>
        <span className="route-chip route-chip-lg">{bus.routeCode}</span>
        <h2 className="grow" style={{ fontSize: '1.15rem' }}>
          Route {bus.routeCode}
          {pattern && <span className="small muted" style={{ display: 'block', fontWeight: 500 }}>towards {pattern.towards}</span>}
        </h2>
        {!gone && <FreshnessBadge epochSeconds={bus.lastUpdatedEpochSeconds} now={now} />}
      </BackHeader>

      {gone && (
        <Notice tone="warn" role="alert">
          This bus is no longer reporting a live position. Its last GPS fix was at {clockText(bus.lastUpdatedEpochSeconds)} ({ageText(bus.lastUpdatedEpochSeconds, now)}), so it is no longer shown on the map.
        </Notice>
      )}

      {routeUnconfirmed(bus) && (
        <Notice tone="warn">
          <strong>Route number unconfirmed.</strong> {OFF_ROUTE_EXPLANATION}
        </Notice>
      )}

      <dl className="facts">
        <dt>Next stop</dt>
        <dd>
          {bus.nextStopName ? (
            <>
              <strong>{bus.nextStopName}</strong>
              {bus.nextStopDistanceMeters != null && ` · ${distanceText(bus.nextStopDistanceMeters)}`}
              <span className="small muted" style={{ display: 'block' }}>{NEXT_STOP_SOURCE[bus.nextStopSource] ?? ''}</span>
            </>
          ) : <span className="muted">Not known — this bus could not be matched to its route&apos;s stops.</span>}
        </dd>
        {bus.nextStopName && (
          <>
            <dt>Arrives there</dt>
            <dd>
              {bus.nextStopEtaMinutes != null
                ? <Eta upcoming={{ etaMinutes: bus.nextStopEtaMinutes, availability: bus.nextStopEtaBasis, lastGpsUpdate: bus.lastUpdatedEpochSeconds }} showSource now={now} />
                : <span className="muted">No reliable estimate</span>}
            </dd>
          </>
        )}
        <dt>Heading</dt>
        <dd>
          {pattern ? <>Towards {pattern.towards} <span className="small muted">(timetable terminus)</span></>
            : bus.direction ? `Direction ${bus.direction}` : <span className="muted">Not reported</span>}
        </dd>
        <dt>Speed</dt>
        <dd>{bus.speedKmh == null ? <span className="muted">Not reported</span> : bus.speedKmh < 1 ? 'Stopped' : `${Math.round(bus.speedKmh)} km/h`}</dd>
        <dt>Last GPS fix</dt>
        <dd>{clockText(bus.lastUpdatedEpochSeconds)} <span className="muted">({ageText(bus.lastUpdatedEpochSeconds, now)})</span></dd>
        <dt>Vehicle</dt>
        <dd>{bus.vehicleId}</dd>
        <dt>Route check</dt>
        <dd>
          {ROUTE_CHECK_TEXT[bus.routeCheck] ?? <span className="muted">Not checked by this server</span>}
        </dd>
      </dl>

      {bus.offRouteFlag && (
        <Notice tone="warn">
          This bus is {distanceText(bus.distanceFromShapeMeters)} from its scheduled route — a diversion, or GPS noise.
        </Notice>
      )}

      <p className="small muted" style={{ margin: 0 }}>
        Position from the Delhi open transit data GPS feed. The destination isn&apos;t part of the live feed; the
        terminus shown comes from the timetable for this route.
      </p>

      <div className="row wrap">
        {!gone && onCentre && <button type="button" className="btn btn-sm" onClick={onCentre}><Icon name="locate" size={16} /> Centre on map</button>}
        {onShowRoute && <button type="button" className="btn btn-sm" onClick={onShowRoute}><Icon name="route" size={16} /> Show route {bus.routeCode}</button>}
      </div>
    </div>
  );
}

/** One stop: next buses (merged across its stand), save, alert, walk there, plan a trip. */
export function StopPanel({ stop, now, onBack, backLabel, onSelectBus, onSelectRoute, nav, gps, liveIds }) {
  const q = usePolling(`arr|${stop.id}`, s => getStopArrivals(stop, s), { interval: 20000 });
  const stand = q.data?.stand;
  const routes = stand?.routeNumbers ?? [];

  return (
    <div className="stack" style={{ gap: 16 }}>
      <BackHeader onBack={onBack} backLabel={backLabel}>
        <span className="stop-badge-icon" aria-hidden="true"><Icon name="pin" size={18} /></span>
        <h2 className="grow" style={{ fontSize: '1.1rem' }}>
          {stop.name}
          <span className="small muted" style={{ display: 'block', fontWeight: 500 }}>
            Stop ID {stop.id}{stand?.members?.length > 1 ? ` · one of ${stand.members.length} stops at this stand` : ''}
          </span>
        </h2>
        <FavStopButton stop={stop} />
      </BackHeader>

      <section aria-labelledby="st-next">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h3 className="section-title" id="st-next" style={{ margin: 0 }}>Next buses</h3>
          {q.updatedAt && <span className="small muted">Updated {ageText(q.updatedAt / 1000, now)}</span>}
        </div>
        <div style={{ marginTop: 8 }}>
          {q.loading && !q.data ? <SkeletonList rows={3} label="Loading arrivals" />
            : q.error && !q.data ? <ErrorState error={q.error} onRetry={q.refresh} what="arrivals" />
            : !stand ? (
              <Notice tone="info">
                Arrival estimates are available for stops in North and North-West Delhi only. This stop is outside that area.
              </Notice>
            ) : (
              <>
                {q.data.feedAvailable === false && (
                  <Notice tone="warn" className="mb">The live feed was interrupted. Times shown use each bus’s last GPS fix and may be out of date.</Notice>
                )}
                <ArrivalList
                  upcoming={stand.upcoming}
                  now={now}
                  onSelect={u => liveIds.has(u.vehicleId) ? onSelectBus(u.vehicleId) : onSelectRoute(u.routeNumber)}
                />
                <IdleRoutes stand={stand} onSelectRoute={onSelectRoute} />
                {q.error && <p className="small muted">Couldn&apos;t refresh just now — showing the last update.</p>}
              </>
            )}
        </div>
      </section>

      <section aria-labelledby="st-alert">
        <h3 className="section-title" id="st-alert">Arrival alert</h3>
        <NotifyControl stop={stop} routes={routes} />
      </section>

      <section aria-labelledby="st-walk">
        <h3 className="section-title" id="st-walk">Get there</h3>
        <WalkSection nav={nav} gps={gps} stop={stop} />
        <div className="row wrap" style={{ marginTop: 8 }}>
          <Link className="btn btn-sm" to={`/journey?to=${encodeURIComponent(placeParam({ ...stop, label: stop.name }))}`}>
            <Icon name="route" size={16} /> Plan a trip to here
          </Link>
        </div>
      </section>
    </div>
  );
}

function WalkSection({ nav, gps, stop }) {
  if (!gps.location) {
    return <p className="small muted" style={{ margin: 0 }}>Share your location to get walking directions to this stop.</p>;
  }
  const walk = nav.walk?.stop?.id === stop.id ? nav.walk : null;
  return (
    <div className="stack" style={{ gap: 8 }}>
      {!nav.active ? (
        <button type="button" className="btn btn-sm" onClick={nav.start} style={{ justifySelf: 'start' }}>
          <Icon name="walk" size={16} /> Walking directions
        </button>
      ) : (
        <button type="button" className="btn btn-sm" onClick={nav.stop} style={{ justifySelf: 'start' }}>
          <Icon name="x" size={16} /> Stop directions
        </button>
      )}
      {nav.busy && <p role="status" className="small muted" style={{ margin: 0 }}>Finding a walking route…</p>}
      {nav.error && <Notice tone="warn" role="alert">{nav.error}</Notice>}
      {walk && (
        <div className="walk-box" role="status">
          <strong>
            {distanceText(nav.remaining ?? walk.distanceMeters)}
            {walk.available ? ' to walk' : ' in a straight line'}
            {nav.secondsRemaining != null
              ? ` · about ${durationText(nav.secondsRemaining / 60)}`
              : walk.distanceMeters != null && ` · about ${durationText(walk.distanceMeters / 83)}`}
          </strong>
          {walk.available && nav.maneuver && !nav.offRoute && <p style={{ margin: '4px 0 0' }}>Next: {nav.maneuver.instruction}</p>}
          {nav.offRoute && <p style={{ margin: '4px 0 0' }}>You are off the route. Recalculating…</p>}
          {!walk.available && <p className="small muted" style={{ margin: '4px 0 0' }}>{walk.message}</p>}
        </div>
      )}
    </div>
  );
}
