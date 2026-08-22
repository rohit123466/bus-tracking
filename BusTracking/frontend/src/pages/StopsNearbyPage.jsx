import { useEffect, useState } from 'react';
import { getNearbyStops, getRoutesServingStop } from '../api/client';

export default function StopsNearbyPage() {
  const [userLocation, setUserLocation] = useState(null);
  const [radius, setRadius] = useState(5000);
  const [stops, setStops] = useState([]);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null);
  const [routesByStop, setRoutesByStop] = useState({});
  const [wheelchairOnly, setWheelchairOnly] = useState(false);

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
    getNearbyStops(userLocation.lat, userLocation.lng, radius, 30)
      .then(setStops)
      .catch(() => setError('Failed to load nearby stops.'));
  };

  useEffect(() => {
    if (userLocation) search();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userLocation]);

  const toggleExpand = (stop) => {
    if (expanded === stop.id) {
      setExpanded(null);
      return;
    }
    setExpanded(stop.id);
    if (!routesByStop[stop.id]) {
      getRoutesServingStop(stop.id)
        .then((routes) => setRoutesByStop((prev) => ({ ...prev, [stop.id]: routes })))
        .catch(() => setRoutesByStop((prev) => ({ ...prev, [stop.id]: [] })));
    }
  };

  return (
    <div className="page-panel">
      <h2>Nearby Bus Stops</h2>
      {error && <div className="banner banner-error" style={{ position: 'static' }}>{error}</div>}

      <div className="inline-form">
        <label>
          Radius (m){' '}
          <input
            type="number"
            value={radius}
            min={100}
            max={5000}
            step={100}
            onChange={(e) => setRadius(Number(e.target.value))}
          />
        </label>
        <button onClick={search} disabled={!userLocation}>
          Refresh
        </button>
      </div>

      <ul className="list-cards">
        {stops
          .filter((stop) => !wheelchairOnly || stop.wheelchairBoarding)
          .map((stop) => (
          <li key={stop.id} className="card">
            <div
              className="card-row"
              onClick={() => toggleExpand(stop)}
              style={{ cursor: 'pointer' }}
              role="button"
              tabIndex={0}
              aria-expanded={expanded === stop.id}
              aria-label={`${stop.name}${stop.wheelchairBoarding ? ', wheelchair accessible boarding' : ''}`}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  toggleExpand(stop);
                }
              }}
            >
              <strong>
                {stop.name}
                {stop.wheelchairBoarding && (
                  <span role="img" aria-label="Wheelchair accessible boarding" title="Wheelchair accessible boarding" style={{ marginLeft: 6 }}>
                    ♿
                  </span>
                )}
              </strong>
              <span aria-hidden="true">{expanded === stop.id ? '▲' : '▼'}</span>
            </div>
            {expanded === stop.id && (
              <div className="card-expanded">
                {!routesByStop[stop.id] && <p>Loading routes…</p>}
                {routesByStop[stop.id]?.length === 0 && <p>No routes recorded for this stop yet.</p>}
                {routesByStop[stop.id]?.map((r) => (
                  <div key={r.routeId} className="card-row">
                    <span>
                      Route {r.routeCode} {r.routeName ? `— ${r.routeName}` : ''}
                    </span>
                    <span>{r.etaMinutes != null ? `${r.etaMinutes} min (${r.nextVehicleId})` : 'No live bus'}</span>
                  </div>
                ))}
              </div>
            )}
          </li>
        ))}
        {stops.length === 0 && <p>No stops found nearby yet.</p>}
        {stops.length > 0 && wheelchairOnly && stops.every((s) => !s.wheelchairBoarding) && (
          <p>No wheelchair-accessible stops found nearby.</p>
        )}
      </ul>
    </div>
  );
}
