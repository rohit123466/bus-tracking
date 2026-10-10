import { useEffect, useMemo, useState } from 'react';
import BusMap from '../components/BusMap';
import SearchBar from '../components/SearchBar';
import TransitStopPicker from '../components/TransitStopPicker';
import { transitGet } from '../api/client';
import useWalkingNavigation from '../hooks/useWalkingNavigation';
import { visibleBuses } from '../utils/navigation';
import './LiveTransit.css';

export default function HomePage() {
  const [routeNumber, setRouteNumber] = useState('');
  const [data, setData] = useState({ buses: [], warnings: [] });
  const [stop, setStop] = useState(null), [destination, setDestination] = useState(null);
  const [arrivals, setArrivals] = useState({ arrivals: [], warnings: [] });
  const [alternatives, setAlternatives] = useState(null);
  const [error, setError] = useState(''), [updated, setUpdated] = useState(null);
  const [routeMap, setRouteMap] = useState(null);
  useEffect(() => {
    setRouteMap(null);
    if (!routeNumber) return;
    const controller = new AbortController();
    transitGet('/routes/map', { routeNumber }, controller.signal).then(r => { if (!controller.signal.aborted) setRouteMap(r); }).catch(() => {});
    return () => controller.abort();
  }, [routeNumber]);
  const navigation = useWalkingNavigation(setStop);
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
  const buses = useMemo(() => visibleBuses(data.buses, routeNumber), [data, routeNumber]);
  const displayedArrivals = arrivals.arrivals.filter(a => !routeNumber || a.routeNumber === routeNumber);
  return <div className="live-transit">
    <aside className="transit-panel">
      <h2>DTC Live Routes</h2>
      <SearchBar publicRoutes onSelectRoute={r => { setRouteNumber(r.routeCode); setStop(null); }} />
      {routeNumber && <button onClick={() => setRouteNumber('')}>Clear route {routeNumber}</button>}
      <p role="status"><strong>{buses.length} active buses</strong>{routeNumber ? ` on route ${routeNumber}` : ' across mapped routes'}{updated && <small>Updated {updated.toLocaleTimeString()}</small>}</p>
      {!buses.length && <p>No matching live buses available.</p>}
      {error && <p role="alert">{error}</p>}
      {data.warnings.map(w => <p className="transit-warning" key={w}>{w}</p>)}
      <TransitStopPicker label="Boarding stop" routeNumber={routeNumber} selected={stop} onSelect={setStop} />
      <TransitStopPicker label="Destination stop" selected={destination} onSelect={setDestination} />
      {stop && <section><h3>Arrivals · {stop.name}</h3>
        {arrivals.warnings.map(w => <p className="transit-warning" key={w}>{w}</p>)}
        {!displayedArrivals.length && <p>No verified approaching vehicles available.</p>}
        {displayedArrivals.map(a => <article className="arrival-card" key={`${a.vehicleId}-${a.tripId}`}>
          <strong>{a.routeNumber} · {a.vehicleId}</strong>
          <div>{a.etaMinutes == null ? 'ETA unavailable' : `${Math.ceil(a.etaMinutes)} min`} · {a.distanceMeters == null ? 'Route distance unavailable' : `${Math.round(a.distanceMeters)} m along route`}</div>
          <small>{a.predictionAvailability} · GPS {new Date(a.lastGpsUpdate * 1000).toLocaleTimeString()}</small>
          {a.reason && <small>{a.reason}</small>}
        </article>)}
      </section>}
      {alternatives && <section><h3>Alternative journeys</h3><p>{alternatives.message}</p>
        {alternatives.options.map((o, i) => <article className="arrival-card" key={i}>
          <strong>{Math.ceil(o.totalMinutes)} min total</strong>
          <div>Walk {o.walkMinutes.toFixed(1)} · Wait {o.waitMinutes.toFixed(1)} · Ride {o.rideMinutes.toFixed(1)} · Transfer {o.transferMinutes.toFixed(1)} min</div>
          {o.legs.map(l => <small key={l.vehicleId}>Route {l.routeNumber}: stop {l.boardStopId} → {l.alightStopId}</small>)}
        </article>)}
      </section>}
      <section><h3>Walk to a bus stop</h3>
        <button onClick={navigation.start} disabled={navigation.active}>Find Nearest Bus Stop</button>
        {navigation.active && <button onClick={navigation.stop}>Stop navigation</button>}
        {navigation.busy && <p role="status">Finding a pedestrian route…</p>}
        {navigation.error && <p role="alert">{navigation.error}</p>}
        {navigation.location && <small>GPS accuracy ±{Math.round(navigation.location.accuracy)} m · {new Date(navigation.location.timestamp).toLocaleTimeString()}</small>}
        {navigation.walk && <div><strong>{navigation.walk.stop?.name}</strong><p>{navigation.walk.message}</p>
          <p>{Math.round(navigation.remaining ?? navigation.walk.distanceMeters ?? 0)} m {navigation.walk.available ? 'walking distance remaining' : '(straight-line distance)'}</p>
          {navigation.secondsRemaining != null && <p>About {Math.ceil(navigation.secondsRemaining / 60)} minutes walking</p>}
          {navigation.offRoute ? <p>Off route. Recalculating when GPS is accurate…</p> : navigation.active && navigation.maneuver && <p>Next: {navigation.maneuver.instruction}</p>}
        </div>}
      </section>
    </aside>
    <div className="transit-map"><BusMap userLocation={navigation.location} buses={buses} selectedStop={stop} walkingRoute={navigation.walk} routeMap={routeMap} onSelectStop={setStop} /></div>
  </div>;
}
