import { useEffect, useRef, useState } from 'react';
import { getBusesPassingNear } from '../api/client';
import WheelchairBadge from '../components/WheelchairBadge';
import LocationPrompt from '../components/transit/LocationPrompt';
import useUserLocation from '../hooks/useUserLocation';

// Location comes from the shared GPS store, which only prompts when the user presses
// "Use my location" (or the browser already granted it); this page never asks on load.
export default function PassingNearMePage() {
  const gps = useUserLocation();
  const userLocation = gps.location;
  const [radius, setRadius] = useState(1000);
  const [buses, setBuses] = useState([]);
  const [error, setError] = useState(null);
  const requestId = useRef(0);

  const search = () => {
    if (!userLocation) return;
    const id = ++requestId.current; // a slower, older answer must not overwrite a newer one
    setError(null);
    getBusesPassingNear(userLocation.lat, userLocation.lng, radius, 30)
      .then(list => { if (id === requestId.current) setBuses(list); })
      .catch(() => { if (id === requestId.current) setError('Failed to load buses passing near you.'); });
  };

  // Search once a position first becomes available, not on every GPS update.
  const hasLocation = !!userLocation;
  useEffect(() => {
    if (hasLocation) search();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasLocation]);

  return (
    <div className="page-panel page-panel-wide">
      <h2>Which Bus Will Pass Near Me</h2>
      <LocationPrompt gps={gps} why="to find buses passing near you" />
      {error && <div className="banner banner-error" style={{ position: 'static' }}>{error}</div>}

      <div className="inline-form">
        <label>
          Radius (m){' '}
          <input
            type="number"
            value={radius}
            min={100}
            step={100}
            onChange={(e) => setRadius(Number(e.target.value))}
          />
        </label>
        <button onClick={search} disabled={!userLocation}>
          Refresh
        </button>
      </div>

      <table className="data-table">
        <thead>
          <tr>
            <th>Route</th>
            <th>Wheelchair Status</th>
            <th>Destination</th>
            <th>Near stop</th>
            <th>Distance</th>
            <th>ETA</th>
          </tr>
        </thead>
        <tbody>
          {buses.map((b, i) => (
            <tr
              key={`${b.vehicleId}-${b.stopName}-${i}`}
              aria-label={
                `Route ${b.routeCode}${b.routeName ? ` — ${b.routeName}` : ''}, ` +
                `heading to ${b.destination || 'unknown destination'}, ` +
                `near ${b.stopName}, ${Math.round(b.distanceToStopMeters)} meters away, ETA ${b.etaMinutes == null ? "unavailable" : b.etaMinutes + " minutes"}` +
                (b.wheelchairAccessible
                  ? `, wheelchair accessible with ${b.wheelchairSpaces} reserved space${b.wheelchairSpaces === 1 ? '' : 's'} for wheelchair users only`
                  : '')
              }
            >
              <td>
                <strong>{b.routeCode}</strong> {b.routeName ? `— ${b.routeName}` : ''}
              </td>
              <td>
                <WheelchairBadge status={b.wheelchairSpaceAvailable} showLabel={true} size="sm" />
              </td>
              <td>{b.destination || '—'}</td>
              <td>{b.stopName}</td>
              <td>{Math.round(b.distanceToStopMeters)} m</td>
              <td>{b.etaMinutes == null ? 'Unavailable' : `≈ ${b.etaMinutes} min (estimate)`}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {buses.length === 0 && <p>No buses found on routes serving nearby stops yet.</p>}
    </div>
  );
}
