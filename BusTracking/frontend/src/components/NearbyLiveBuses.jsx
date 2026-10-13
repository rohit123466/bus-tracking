/**
 * NearbyLiveBuses — shows GTFS-verified live buses near the user's GPS location.
 *
 * Uses GET /api/transit/buses/nearby which calls LiveTransitService.nearbyBuses().
 * Distance is computed server-side using Haversine against live vehicle coordinates.
 * This component is separate from the route-search (Live Bus Search) — the two are
 * independent business functions and must not share filtering logic.
 */
import { useEffect, useState, useRef } from 'react';
import { getNearbyLiveBuses } from '../api/client';

const RADIUS_OPTIONS = [500, 1000, 2000, 5000, 10000];

function formatAge(epochSeconds) {
  if (!epochSeconds) return 'unknown';
  const age = Math.floor(Date.now() / 1000 - epochSeconds);
  if (age < 60) return `${age}s ago`;
  return `${Math.floor(age / 60)}m ago`;
}

export default function NearbyLiveBuses({ userLocation, routeNumber = '', onSelectBus }) {
  const [result, setResult]       = useState(null);
  const [radius, setRadius]       = useState(2000);
  const [error, setError]         = useState('');
  const [loading, setLoading]     = useState(false);
  const abortRef                  = useRef(null);
  const timerRef                  = useRef(null);

  useEffect(() => {
    if (!userLocation) return;
    const poll = async () => {
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setLoading(true);
      try {
        const data = await getNearbyLiveBuses(
          userLocation.lat, userLocation.lng, radius, routeNumber
        );
        if (!ctrl.signal.aborted) { setResult(data); setError(''); }
      } catch (e) {
        if (!ctrl.signal.aborted)
          setError('Could not fetch nearby buses. ' + (e?.response?.data?.message || ''));
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    };
    poll();
    timerRef.current = setInterval(poll, 10000);
    return () => {
      abortRef.current?.abort();
      clearInterval(timerRef.current);
    };
  }, [userLocation, radius, routeNumber]);

  if (!userLocation) return (
    <div className="nearby-buses">
      <p className="nearby-hint">Allow location access to see live buses near you.</p>
    </div>
  );

  return (
    <div className="nearby-buses">
      <div className="nearby-header">
        <strong>Nearby Live Buses</strong>
        <select
          value={radius}
          onChange={e => setRadius(Number(e.target.value))}
          aria-label="Search radius"
          className="nearby-radius-select"
        >
          {RADIUS_OPTIONS.map(r => (
            <option key={r} value={r}>{r >= 1000 ? `${r / 1000} km` : `${r} m`}</option>
          ))}
        </select>
      </div>

      {loading && !result && <p role="status">Finding buses…</p>}
      {error && <p role="alert" className="transit-warning">{error}</p>}

      {result && (
        <>
          {result.warnings.map(w => (
            <p key={w} className="transit-warning">{w}</p>
          ))}
          {result.buses.length === 0 ? (
            <p>No live buses within {radius >= 1000 ? `${radius / 1000} km` : `${radius} m`}.</p>
          ) : (
            <ul className="nearby-bus-list" aria-label="Nearby buses">
              {result.buses.map(bus => (
                <li
                  key={bus.vehicleId}
                  className="nearby-bus-item"
                  onClick={() => onSelectBus?.(bus)}
                  tabIndex={0}
                  onKeyDown={e => e.key === 'Enter' && onSelectBus?.(bus)}
                  role="button"
                  aria-label={`Route ${bus.routeCode}, ${Math.round(bus.distanceMeters)} m away`}
                >
                  <span className="nearby-route-badge">{bus.routeCode}</span>
                  <span className="nearby-dist">{Math.round(bus.distanceMeters)} m</span>
                  {bus.direction && <span className="nearby-dir">{bus.direction}</span>}
                  {bus.speedKmh != null && (
                    <span className="nearby-speed">{Math.round(bus.speedKmh)} km/h</span>
                  )}
                  <span className="nearby-age">{formatAge(bus.lastUpdatedEpochSeconds)}</span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );
}
