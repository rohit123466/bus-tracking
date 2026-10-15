/**
 * HomePage — DTC Live Transit main page.
 *
 * Integrates:
 *  F1  Nearby Live Buses  (NearbyLiveBuses component, /api/transit/buses/nearby)
 *  F2  Next Bus Arrivals  (ArrivalCards — enhanced with remainingStops, confidence)
 *  F3  ETA confidence     (shown in ArrivalCards via predictionAvailability)
 *  F4  Route matching     (existing SearchBar publicRoutes mode — exact GTFS match)
 *  F7  Favourites         (useFavourites hook + FavouritesPanel)
 *  F8  Notifications      (useArrivalNotifications inside ArrivalCards)
 *  F13 Service Alerts     (ServiceAlertsPanel + /api/transit/alerts)
 */
import { useEffect, useMemo, useState, useCallback } from 'react';
import BusMap from '../components/BusMap';
import SearchBar from '../components/SearchBar';
import TransitStopPicker from '../components/TransitStopPicker';
import ArrivalCards from '../components/ArrivalCards';
import NearbyLiveBuses from '../components/NearbyLiveBuses';
import NearestBusStand from '../components/NearestBusStand';
import FavouritesPanel from '../components/FavouritesPanel';
import ServiceAlertsPanel from '../components/ServiceAlertsPanel';
import { transitGet, getServiceAlerts } from '../api/client';
import useWalkingNavigation from '../hooks/useWalkingNavigation';
import useUserLocation from '../hooks/useUserLocation';
import useFavourites from '../hooks/useFavourites';
import { visibleBuses } from '../utils/navigation';
import { REGION_OPTIONS, DEFAULT_REGION, isRegional, regionLabel } from '../utils/liveMap';
import './LiveTransit.css';

export default function HomePage() {
  const [routeNumber, setRouteNumber] = useState('');
  // Route-based region filter; applies when no single route is searched.
  const [region, setRegion] = useState(DEFAULT_REGION);
  const [data, setData] = useState({ buses: [], warnings: [] });
  const [stop, setStop] = useState(null), [destination, setDestination] = useState(null);
  const [arrivals, setArrivals] = useState({ arrivals: [], warnings: [] });
  const [alternatives, setAlternatives] = useState(null);
  const [error, setError] = useState(''), [updated, setUpdated] = useState(null);
  const [routeMap, setRouteMap] = useState(null);
  const [alertsResult, setAlertsResult] = useState(null);
  const [showFavs, setShowFavs] = useState(false);
  const [showNearby, setShowNearby] = useState(true);
  const [nearbyStops, setNearbyStops] = useState([]);

  const gps = useUserLocation();
  // Pass the selected boarding stop as `targetStop` so walking directions
  // route directly to it instead of hunting for the "nearest" stop.
  const navigation = useWalkingNavigation(setStop, gps.location, stop);
  const favs = useFavourites();

  // ── Route map polyline ──────────────────────────────────────────────────────
  useEffect(() => {
    setRouteMap(null);
    if (!routeNumber) return;
    const ctrl = new AbortController();
    transitGet('/routes/map', { routeNumber }, ctrl.signal)
      .then(r => { if (!ctrl.signal.aborted) setRouteMap(r); })
      .catch(() => {});
    return () => ctrl.abort();
  }, [routeNumber]);

  // ── Live bus polling ────────────────────────────────────────────────────────
  useEffect(() => {
    const ctrl = new AbortController(); let timer;
    setData({ buses: [], warnings: [] });
    // A searched route keeps the original endpoint; otherwise show region-eligible routes,
    // wherever in Delhi their buses currently are.
    const regional = !routeNumber && isRegional(region);
    const poll = async () => {
      try {
        const result = regional
          ? await transitGet('/buses/regional', { region }, ctrl.signal)
          : await transitGet('/buses/live', { routeNumber }, ctrl.signal);
        if (!ctrl.signal.aborted) { setData(result); setUpdated(new Date()); setError(''); }
      } catch {
        if (!ctrl.signal.aborted) { setData({ buses: [], warnings: [] }); setError('Live bus feed unavailable. Please retry.'); }
      } finally {
        if (!ctrl.signal.aborted) timer = setTimeout(poll, 10000);
      }
    };
    poll();
    return () => { ctrl.abort(); clearTimeout(timer); };
  }, [routeNumber, region]);

  // ── Arrivals + alternatives polling ────────────────────────────────────────
  useEffect(() => {
    setArrivals({ arrivals: [], warnings: [] }); setAlternatives(null);
    if (!stop) return;
    const ctrl = new AbortController(); let timer; let lastAlt = 0;
    const poll = async () => {
      try {
        const result = await transitGet(`/stops/${encodeURIComponent(stop.id)}/arrivals`, {}, ctrl.signal);
        if (ctrl.signal.aborted) return;
        setArrivals(result);
        const suitable = result.arrivals.filter(a => !routeNumber || a.routeNumber === routeNumber);
        const earliest = suitable.filter(a => a.etaMinutes != null)
          .reduce((m, a) => Math.min(m, a.etaMinutes), Infinity);
        if (earliest > 15 && Number.isFinite(earliest) && Date.now() - lastAlt > 30000) {
          lastAlt = Date.now();
          const opts = await transitGet('/routes/alternatives',
            { sourceStopId: stop.id, destinationStopId: destination?.id, routeNumber }, ctrl.signal);
          if (!ctrl.signal.aborted) setAlternatives(opts);
        } else if (earliest <= 15 || !Number.isFinite(earliest)) setAlternatives(null);
      } catch {
        if (!ctrl.signal.aborted) {
          setArrivals({ arrivals: [], warnings: ['Arrival predictions unavailable.'] });
          setAlternatives(null);
        }
      } finally {
        if (!ctrl.signal.aborted) timer = setTimeout(poll, 10000);
      }
    };
    poll();
    return () => { ctrl.abort(); clearTimeout(timer); };
  }, [stop, destination, routeNumber]);

  // ── Service alerts (poll every 30s) ────────────────────────────────────────
  useEffect(() => {
    let timer;
    const fetch = async () => {
      try {
        const res = await getServiceAlerts(routeNumber);
        setAlertsResult(res);
      } catch { /* non-critical */ }
      timer = setTimeout(fetch, 30000);
    };
    fetch();
    return () => clearTimeout(timer);
  }, [routeNumber]);

  const buses = useMemo(() => visibleBuses(data.buses, routeNumber), [data, routeNumber]);
  const displayedArrivals = arrivals.arrivals.filter(a => !routeNumber || a.routeNumber === routeNumber);

  // ── Favourites actions ──────────────────────────────────────────────────────
  const handleSelectRoute = useCallback((code) => {
    setRouteNumber(code);
    setStop(null);
    favs.addRecent(code, 'route');
  }, [favs]);

  const handleSelectStop = useCallback((s) => {
    setStop(s);
    favs.addRecent(s.name, 'stop');
  }, [favs]);

  const handleSaveHome = () => {
    if (gps.location) favs.saveHome({ label: 'Home', lat: gps.location.lat, lng: gps.location.lng });
  };
  const handleSaveOffice = () => {
    if (gps.location) favs.saveOffice({ label: 'Office', lat: gps.location.lat, lng: gps.location.lng });
  };

  return (
    <div className="live-transit">
      <aside className="transit-panel">
        <h2>DTC Live Routes</h2>

        {/* GPS status */}
        {gps.error && <p role="alert" className="transit-warning">{gps.error}</p>}

        {/* Service Alerts */}
        <ServiceAlertsPanel alertsResult={alertsResult} />

        {/* Route search */}
        <SearchBar
          publicRoutes
          onSelectRoute={r => handleSelectRoute(r.routeCode)}
        />
        {routeNumber && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
            <button onClick={() => { setRouteNumber(''); setStop(null); }}>
              ✕ Clear route {routeNumber}
            </button>
            <button
              title={favs.isRouteFav(routeNumber) ? 'Remove from favourites' : 'Save to favourites'}
              onClick={() => favs.toggleRoute(routeNumber)}
              style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 18 }}
              aria-label={favs.isRouteFav(routeNumber) ? 'Remove favourite route' : 'Add favourite route'}
            >
              {favs.isRouteFav(routeNumber) ? '⭐' : '☆'}
            </button>
          </div>
        )}

        {/* Region filter (route-based: a route qualifies by its stops, not the bus's position) */}
        <label className="region-filter">
          Show routes
          <select
            value={region}
            onChange={e => setRegion(e.target.value)}
            aria-describedby="region-note"
          >
            {REGION_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </label>
        <p id="region-note" className="region-note">
          {routeNumber
            ? `Showing searched route ${routeNumber}; clear it to use the region filter.`
            : isRegional(region)
              ? 'Includes buses anywhere in Delhi whose route stops in this area.'
              : 'All routes with a verified GTFS route number.'}
        </p>

        {/* Live bus count */}
        <p role="status">
          <strong>{buses.length} active bus{buses.length !== 1 ? 'es' : ''}</strong>
          {routeNumber
            ? ` on route ${routeNumber}`
            : isRegional(region)
              ? ` on ${data.eligibleRouteCount ?? '…'} ${regionLabel(region).replace(/ routes$/, '')} routes`
              : ' across mapped routes'}
          {updated && <small> · Updated {updated.toLocaleTimeString()}</small>}
        </p>
        {!buses.length && routeNumber && (
          <p>No live buses currently reporting for route {routeNumber}.</p>
        )}
        {data.feedAvailable === false && (
          <p role="alert" className="transit-warning">
            Live vehicle positions are unavailable right now. Route and stop information below is
            static GTFS data; nothing on the map is shown as a live bus until the feed returns.
          </p>
        )}
        {error && <p role="alert">{error}</p>}
        {data.warnings.map(w => <p className="transit-warning" key={w}>{w}</p>)}

        {/* Favourites */}
        <button className="fav-toggle" onClick={() => setShowFavs(v => !v)}>
          ⭐ Favourites & Shortcuts {showFavs ? '▲' : '▼'}
        </button>
        {showFavs && (
          <FavouritesPanel
            favourites={favs}
            onSelectRoute={handleSelectRoute}
            onSelectStop={handleSelectStop}
            onUseHome={() => favs.home && navigation.start()}
            onUseOffice={() => favs.office && navigation.start()}
            onSaveCurrentAsHome={handleSaveHome}
            onSaveCurrentAsOffice={handleSaveOffice}
          />
        )}

        {/* Nearest Bus Stands — auto-triggers on GPS, shows stops sorted nearest-first */}
        <section>
          <NearestBusStand
            userLocation={gps.location}
            onSelectStop={handleSelectStop}
            onStopsFound={setNearbyStops}
            selectedStopId={stop?.id}
            region={region}
          />
        </section>

        {/* Nearby Live Buses */}
        <section>
          <button className="fav-toggle" onClick={() => setShowNearby(v => !v)}>
            📡 Nearby Live Buses {showNearby ? '▲' : '▼'}
          </button>
          {showNearby && (
            <NearbyLiveBuses
              userLocation={gps.location}
              routeNumber={routeNumber}
              onSelectBus={bus => handleSelectRoute(bus.routeCode)}
            />
          )}
        </section>

        {/* Stop pickers */}
        <TransitStopPicker
          label="Boarding stop"
          routeNumber={routeNumber}
          selected={stop}
          onSelect={s => { handleSelectStop(s); }}
        />
        <TransitStopPicker
          label="Destination stop"
          selected={destination}
          onSelect={setDestination}
        />

        {/* Stop favourite button */}
        {stop && (
          <button
            style={{ background: 'none', border: 'none', cursor: 'pointer', fontSize: 13, marginBottom: 4 }}
            onClick={() => favs.toggleStop(stop)}
          >
            {favs.isStopFav(stop.id) ? '⭐' : '☆'} {favs.isStopFav(stop.id) ? 'Saved' : 'Save'} stop
          </button>
        )}

        {/* Arrivals (Feature 2 — enhanced ArrivalCards) */}
        {stop && (
          <section>
            <h3>Arrivals · {stop.name}</h3>
            <ArrivalCards
              arrivals={displayedArrivals}
              warnings={arrivals.warnings}
              stopName={stop.name}
              routeNumber={routeNumber}
              limit={5}
            />
          </section>
        )}

        {/* Alternatives */}
        {alternatives && (
          <section>
            <h3>Alternative journeys</h3>
            <p>{alternatives.message}</p>
            {alternatives.baselineMinutes != null && (
              <p>Original journey: {Math.ceil(alternatives.baselineMinutes)} min</p>
            )}
            {alternatives.options.map((o, i) => (
              <article className="arrival-card" key={i}>
                <strong>{Math.ceil(o.totalMinutes)} min total</strong>
                <div>
                  Walk {o.walkMinutes.toFixed(1)} · Wait {o.waitMinutes.toFixed(1)} ·
                  Ride {o.rideMinutes.toFixed(1)} · Transfer {o.transferMinutes.toFixed(1)} min
                </div>
                {o.legs.map(l => (
                  <small key={l.vehicleId}>
                    Route {l.routeNumber}: stop {l.boardStopId} → {l.alightStopId}
                  </small>
                ))}
              </article>
            ))}
          </section>
        )}

        {/* Walking navigation — turn-by-turn to the selected stop */}
        <section>
          <h3>Walk to stop</h3>

          {/* Context-aware prompt */}
          {!stop && !navigation.active && (
            <p style={{ fontSize: 13, color: '#888', margin: '0 0 6px' }}>
              Select a stop above, then tap below for walking directions.
            </p>
          )}
          {stop && !navigation.active && (
            <p style={{ fontSize: 13, color: '#16835b', margin: '0 0 6px' }}>
              🚏 Walking to: <strong>{stop.name}</strong>
            </p>
          )}

          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button
              onClick={navigation.start}
              disabled={navigation.active || !gps.location}
              title={!gps.location ? 'Waiting for GPS' : stop ? `Walk to ${stop.name}` : 'Find nearest stop'}
            >
              {navigation.active
                ? '🗺️ Navigating…'
                : stop
                  ? `🗺️ Walk to ${stop.name.length > 20 ? stop.name.slice(0, 18) + '…' : stop.name}`
                  : '🗺️ Find nearest stop'}
            </button>
            {navigation.active && (
              <button onClick={navigation.stop}>✕ Stop</button>
            )}
          </div>

          {navigation.busy && <p role="status" style={{ fontSize: 13, color: '#888', marginTop: 4 }}>Finding route…</p>}
          {navigation.error && <p role="alert" className="transit-warning" style={{ marginTop: 4 }}>{navigation.error}</p>}

          {/* GPS accuracy */}
          {gps.location && (
            <small style={{ color: '#aaa', display: 'block', marginTop: 4 }}>
              GPS ±{Math.round(gps.location.accuracy)} m · {new Date(gps.location.timestamp).toLocaleTimeString()}
            </small>
          )}

          {/* Active navigation result */}
          {navigation.walk && (
            <div style={{ marginTop: 8, padding: '8px 10px', background: '#f0f8ff', borderRadius: 8, border: '1px solid #c7d2fe' }}>
              <strong style={{ fontSize: 14 }}>📍 {navigation.walk.stop?.name}</strong>

              {/* Straight-line fallback — still useful even without ORS */}
              {!navigation.walk.available && navigation.walk.distanceMeters != null && (
                <div style={{ fontSize: 13, marginTop: 4 }}>
                  <div style={{ color: '#1a56db', fontWeight: 600 }}>
                    📏 {Math.round(navigation.walk.distanceMeters)} m away (straight-line)
                  </div>
                  <div style={{ color: '#16835b', fontWeight: 600 }}>
                    🚶 ~{Math.ceil(navigation.walk.distanceMeters / 83)} min walk
                  </div>
                  <div style={{ fontSize: 12, color: '#888', marginTop: 3 }}>
                    {navigation.walk.message}
                  </div>
                </div>
              )}

              {/* Full pedestrian route available */}
              {navigation.walk.available && (
                <>
                  <div style={{ display: 'flex', gap: 12, fontSize: 13, marginTop: 4 }}>
                    <span style={{ color: '#1a56db', fontWeight: 600 }}>
                      📏 {Math.round(navigation.remaining ?? navigation.walk.distanceMeters ?? 0)} m remaining
                    </span>
                    {navigation.secondsRemaining != null && (
                      <span style={{ color: '#16835b', fontWeight: 600 }}>
                        🚶 ~{Math.ceil(navigation.secondsRemaining / 60)} min
                      </span>
                    )}
                  </div>
                  {navigation.offRoute
                    ? <p style={{ fontSize: 12, color: '#c58414', marginTop: 4 }}>⚠️ Off route. Recalculating…</p>
                    : navigation.active && navigation.maneuver && (
                        <p style={{ fontSize: 13, marginTop: 4 }}>
                          ↪ <strong>{navigation.maneuver.instruction}</strong>
                          {navigation.maneuver.distance > 0 && (
                            <span style={{ color: '#888' }}> ({Math.round(navigation.maneuver.distance)} m)</span>
                          )}
                        </p>
                      )
                  }
                </>
              )}
            </div>
          )}
        </section>
      </aside>

      <div className="transit-map">
        <BusMap
          userLocation={gps.location}
          buses={buses}
          selectedStop={stop ?? navigation.walk?.stop}
          walkingRoute={navigation.walk}
          routeMap={routeMap}
          nearbyStops={nearbyStops}
          onSelectStop={handleSelectStop}
          onLocate={gps.locate}
          locating={gps.locating}
          liveStatus
        />
      </div>
    </div>
  );
}
