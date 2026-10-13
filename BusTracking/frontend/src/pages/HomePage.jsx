import { useEffect, useMemo, useState } from 'react';
import BusMap from '../components/BusMap';
import SearchBar from '../components/SearchBar';
import TransitStopPicker from '../components/TransitStopPicker';
import { transitGet } from '../api/client';
import useWalkingNavigation from '../hooks/useWalkingNavigation';
import useUserLocation from '../hooks/useUserLocation';
import { visibleBuses, nearbyBuses } from '../utils/navigation';
import './LiveTransit.css';

export default function HomePage() {
  const [routeNumber, setRouteNumber] = useState('');
  const [nearbyOnly, setNearbyOnly] = useState(true);
  const [radius, setRadius] = useState(2000);
  const [data, setData] = useState({ buses: [], warnings: [] });
  const [stop, setStop] = useState(null), [destination, setDestination] = useState(null);
  const [arrivals, setArrivals] = useState({ arrivals: [], warnings: [] });
  const [alternatives, setAlternatives] = useState(null);
  const [error, setError] = useState(''), [updated, setUpdated] = useState(null);
  const [routeMap, setRouteMap] = useState(null);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    setRouteMap(null);
    if (!routeNumber) return;
    const controller = new AbortController();
    transitGet('/routes/map', { routeNumber }, controller.signal).then(r => { if (!controller.signal.aborted) setRouteMap(r); }).catch(() => {});
    return () => controller.abort();
  }, [routeNumber]);
  const gps = useUserLocation();
  const navigation = useWalkingNavigation(setStop, gps.location);
  useEffect(() => {
    const controller = new AbortController(); let timer;
    setData({ buses: [], warnings: [] });
    const poll = async () => {
      try {
        const result = await transitGet('/buses/live', { routeNumber }, controller.signal);
        if (!controller.signal.aborted) { setData(result); setUpdated(new Date()); setError(''); }
      } catch { if (!controller.signal.aborted) { setData({ buses: [], warnings: [] }); setError('Live bus feed unavailable. Please retry.'); } }
      finally { if (!controller.signal.aborted) timer = setTimeout(poll, 10000); }
    };
    poll(); return () => { controller.abort(); clearTimeout(timer); };
  }, [routeNumber]);
  useEffect(() => {
    setArrivals({ arrivals: [], warnings: [] }); setAlternatives(null);
    if (!stop) return;
    const controller = new AbortController(); let timer; let lastAlternatives = 0;
    const poll = async () => {
      try {
        const result = await transitGet(`/stops/${encodeURIComponent(stop.id)}/arrivals`, {}, controller.signal);
        if (controller.signal.aborted) return;
        setArrivals(result);
        const suitable = result.arrivals.filter(a => !routeNumber || a.routeNumber === routeNumber);
        const earliest = suitable.filter(a => a.etaMinutes != null).reduce((min, a) => Math.min(min, a.etaMinutes), Infinity);
        if (earliest > 15 && Number.isFinite(earliest) && Date.now() - lastAlternatives > 30000) {
          lastAlternatives = Date.now();
          const options = await transitGet('/routes/alternatives', { sourceStopId: stop.id, destinationStopId: destination?.id, routeNumber }, controller.signal);
          if (!controller.signal.aborted) setAlternatives(options);
        } else if (earliest <= 15 || !Number.isFinite(earliest)) setAlternatives(null);
      } catch { if (!controller.signal.aborted) { setArrivals({ arrivals: [], warnings: ['Arrival predictions unavailable.'] }); setAlternatives(null); } }
      finally { if (!controller.signal.aborted) timer = setTimeout(poll, 10000); }
    };
    poll(); return () => { controller.abort(); clearTimeout(timer); };
  }, [stop, destination, routeNumber]);
  const mappedBuses = useMemo(() => visibleBuses(data.buses, routeNumber, now / 1000), [data, routeNumber, now]);
  const locationFresh = gps.location && now - gps.location.timestamp <= 30000;
  const buses = useMemo(() => nearbyOnly ? nearbyBuses(mappedBuses, gps.location, radius, now) : mappedBuses,
    [mappedBuses, nearbyOnly, gps.location, radius, now]);
  const displayedArrivals = arrivals.arrivals.filter(a => (!routeNumber || a.routeNumber === routeNumber)
    && now / 1000 - a.lastGpsUpdate <= 120 && now / 1000 - a.lastGpsUpdate >= -30);
  return <div className="live-transit">
    <aside className="transit-panel">
      <h2>DTC Live Routes</h2>
      {gps.error && <p role="alert">{gps.error}</p>}
      <label><input type="checkbox" checked={nearbyOnly} onChange={e => setNearbyOnly(e.target.checked)} style={{ width: 'auto' }} /> Show buses near my location</label>
      {nearbyOnly && <>
        <label>Nearby radius<select aria-label="Nearby radius" value={radius} onChange={e => setRadius(Number(e.target.value))}>
          <option value={500}>500 metres</option><option value={1000}>1 kilometre</option><option value={2000}>2 kilometres</option><option value={5000}>5 kilometres</option><option value={10000}>10 kilometres</option>
        </select></label>
        {!locationFresh && <p role="status">Waiting for a recent location fix to show nearby buses. Allow browser location access.</p>}
        {locationFresh && <small>Distances from your GPS position ({gps.location.lat.toFixed(5)}, {gps.location.lng.toFixed(5)}). Straight-line proximity does not confirm a bus will reach your stop.</small>}
      </>}
      <SearchBar publicRoutes onSelectRoute={r => { setRouteNumber(r.routeCode); setStop(null); }} />
      {routeNumber && <button onClick={() => setRouteNumber('')}>Clear route {routeNumber}</button>}
      <p role="status"><strong>{buses.length} recently reported buses</strong>{routeNumber ? ` on route ${routeNumber}` : (nearbyOnly ? ` within ${radius / 1000} km of your location` : ' across mapped routes')}{updated && <small>Updated {updated.toLocaleTimeString()}</small>}</p>
      {nearbyOnly && <small>Only verified vehicle reports from the last 2 minutes are shown. A recent report does not confirm movement or passenger service.</small>}
      {!buses.length && <p>{nearbyOnly && locationFresh ? 'No verified live buses within this radius. Try a larger radius or turn off nearby filtering.' : 'No matching live buses available.'}</p>}
      {nearbyOnly && buses.length > 0 && <section aria-label="Nearby live buses"><h3>Nearest live buses</h3>
        {buses.slice(0, 20).map(b => <article className="arrival-card" key={b.vehicleId}>
          <strong>Route {b.routeCode} · {b.vehicleId}</strong>
          <div>{Math.round(b.distanceToUser)} m from you · {b.direction || 'Direction unknown'}</div>
          <small>GPS updated {new Date(b.lastUpdatedEpochSeconds * 1000).toLocaleTimeString()} · {Math.max(0, Math.floor(now / 1000 - b.lastUpdatedEpochSeconds))} seconds ago</small>
        </article>)}
        {buses.length > 20 && <small>Closest 20 listed; all matching buses are on the map.</small>}
      </section>}
      {error && <p role="alert">{error}</p>}
      {data.warnings.map(w => <p className="transit-warning" key={w}>{w}</p>)}
      <TransitStopPicker label="Boarding stop" routeNumber={routeNumber} selected={stop} onSelect={setStop} />
      <TransitStopPicker label="Destination stop" selected={destination} onSelect={setDestination} />
      {stop && <section><h3>Arrivals · {stop.name}</h3>
        {arrivals.warnings.map(w => <p className="transit-warning" key={w}>{w}</p>)}
        {!displayedArrivals.length && <p>No verified approaching vehicles available.</p>}
        {displayedArrivals.map(a => <article className="arrival-card" key={`${a.vehicleId}-${a.tripId}`}>
          <strong>{a.routeNumber} · {a.vehicleId}</strong>
          <div>{a.etaMinutes == null ? 'ETA unavailable' : `${Math.ceil(a.etaMinutes)} min`} · {a.distanceMeters == null ? 'Route distance unavailable' : `${(a.distanceMeters / 1000).toFixed(2)} km along route`}</div>
          <small>{a.direction || 'Direction unknown'}{Number.isFinite(a.busLatitude) && Number.isFinite(a.busLongitude) && ` · Position ${a.busLatitude.toFixed(5)}, ${a.busLongitude.toFixed(5)}`}</small>
          <small>{a.predictionAvailability} · GPS {new Date(a.lastGpsUpdate * 1000).toLocaleTimeString()}</small>
          {a.reason && <small>{a.reason}</small>}
        </article>)}
      </section>}
      {alternatives && <section><h3>Alternative journeys</h3><p>{alternatives.message}</p>{alternatives.baselineMinutes != null && <p>Original journey: {Math.ceil(alternatives.baselineMinutes)} min</p>}
        {alternatives.options.map((o, i) => <article className="arrival-card" key={i}>
          <strong>{Math.ceil(o.totalMinutes)} min total</strong>
          {alternatives.baselineMinutes != null && <small>Estimated saving: {Math.floor(alternatives.baselineMinutes - o.totalMinutes)} min</small>}
          {o.walkingDistanceMeters != null && <small>Walk {(o.walkingDistanceMeters / 1000).toFixed(2)} km from the selected boarding stop</small>}
          <div>Walk {o.walkMinutes.toFixed(1)} · Wait {o.waitMinutes.toFixed(1)} · Ride {o.rideMinutes.toFixed(1)} · Transfer {o.transferMinutes.toFixed(1)} min</div>
          {o.legs.map(l => <small key={l.vehicleId}>Route {l.routeNumber}: {l.boardStopName || l.boardStopId} → {l.alightStopName || l.alightStopId}</small>)}
        </article>)}
      </section>}
      <section><h3>Walk to a bus stop</h3>
        <button onClick={navigation.start} disabled={navigation.busy}>Find Nearest Bus Stop</button>
        {navigation.active && <button onClick={navigation.stop}>Stop navigation</button>}
        {navigation.busy && <p role="status">Finding a pedestrian route…</p>}
        {navigation.error && <p role="alert">{navigation.error}</p>}
        {navigation.location && <small>Location updated {new Date(navigation.location.timestamp).toLocaleTimeString()}</small>}
        {navigation.walk && <div><strong>{navigation.walk.stop?.name}</strong><p>{navigation.walk.message}</p>
          {navigation.walk.stop && <p>{Math.round(navigation.remaining ?? navigation.walk.distanceMeters)} m {navigation.walk.available ? 'walking distance remaining' : '(straight-line distance; pedestrian access unverified)'}</p>}
          {navigation.secondsRemaining != null && <p>About {Math.ceil(navigation.secondsRemaining / 60)} minutes walking</p>}
          {navigation.offRoute ? <p>Off route. Recalculating when GPS is accurate…</p> : navigation.active && navigation.maneuver && <p>Next: {navigation.maneuver.instruction}</p>}
        </div>}
      </section>
    </aside>
    <div className="transit-map"><BusMap userLocation={navigation.location} buses={buses} selectedStop={stop} walkingRoute={navigation.walk} routeMap={routeMap} onSelectStop={setStop} /></div>
  </div>;
}
