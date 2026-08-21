import { useEffect, useState } from 'react';
import { getBusesPassingNear } from '../api/client';
import WheelchairBadge from '../components/WheelchairBadge';

export default function PassingNearMePage() {
  const [userLocation, setUserLocation] = useState(null);
  const [radius, setRadius] = useState(1000);
  const [buses, setBuses] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!navigator.geolocation) {
      setError('Geolocation is not supported by this browser.');
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => setUserLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude }),
      (err) => setError(err.message)
    );
  }, []);

  const search = () => {
    if (!userLocation) return;
    getBusesPassingNear(userLocation.lat, userLocation.lng, radius, 30)
      .then(setBuses)
      .catch(() => setError('Failed to load buses passing near you.'));
  };

  useEffect(() => {
    if (userLocation) search();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userLocation]);

  return (
    <div className="page-panel page-panel-wide">
      <h2>Which Bus Will Pass Near Me</h2>
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
            <tr key={`${b.vehicleId}-${b.stopName}-${i}`}>
              <td>
                <strong>{b.routeCode}</strong> {b.routeName ? `— ${b.routeName}` : ''}
              </td>
              <td>
                <WheelchairBadge status={b.wheelchairSpaceAvailable} showLabel={true} size="sm" />
              </td>
              <td>{b.destination || '—'}</td>
              <td>{b.stopName}</td>
              <td>{Math.round(b.distanceToStopMeters)} m</td>
              <td>{b.etaMinutes} min</td>
            </tr>
          ))}
        </tbody>
      </table>
      {buses.length === 0 && <p>No buses found on routes serving nearby stops yet.</p>}
    </div>
  );
}
