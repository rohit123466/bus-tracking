/**
 * StopsNearbyPage — full-page view of bus stops near the user.
 * Uses GET /api/transit/stops/nearby (GTFS index, enriched with routes + live count).
 * Tapping a stop loads arrivals from GET /api/transit/stops/{id}/arrivals.
 */
import { useEffect, useState } from 'react';
import { getNearbyGtfsStops, transitGet } from '../api/client';
import ArrivalCards from '../components/ArrivalCards';

const RADIUS_OPTIONS = [
  { value: 300,  label: '300 m' },
  { value: 500,  label: '500 m' },
  { value: 1000, label: '1 km'  },
  { value: 2000, label: '2 km'  },
  { value: 5000, label: '5 km'  },
];

function distLabel(m) {
  if (m < 1000) return `${Math.round(m)} m`;
  return `${(m / 1000).toFixed(1)} km`;
}

export default function StopsNearbyPage() {
  const [userLocation, setUserLocation]     = useState(null);
  const [gpsError, setGpsError]             = useState('');
  const [radius, setRadius]                 = useState(1000);
  const [stops, setStops]                   = useState([]);
  const [feedAvailable, setFeedAvailable]   = useState(true);
  const [loading, setLoading]               = useState(false);
  const [error, setError]                   = useState('');
  const [selectedStop, setSelectedStop]     = useState(null);
  const [arrivals, setArrivals]             = useState(null);
  const [arrivalsLoading, setArrivalsLoading] = useState(false);
  const [wheelchairOnly, setWheelchairOnly] = useState(false);

  // ── GPS ───────────────────────────────────────────────────────────────────
  useEffect(() => {
    if (!navigator.geolocation) {
      setGpsError('Geolocation is not supported by this browser.');
      return;
    }
    const watch = navigator.geolocation.watchPosition(
      pos => {
        setUserLocation({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy });
        setGpsError('');
      },
      err => setGpsError(
        err.code === 1
          ? 'Location permission denied. Enable it in your browser settings.'
          : 'Could not obtain GPS location.'
      ),
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 15000 }
    );
    return () => navigator.geolocation.clearWatch(watch);
  }, []);

  // ── Fetch stops ──────────────────────────────────────────────────────────
  const fetchStops = () => {
    if (!userLocation) return;
    setLoading(true);
    setError('');
    getNearbyGtfsStops(userLocation.lat, userLocation.lng, radius, 20)
      .then(res => {
        setStops(res.stops);
        setFeedAvailable(res.feedAvailable);
      })
      .catch(() => setError('Failed to load nearby stops.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (userLocation) fetchStops();
  }, [userLocation, radius]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Load arrivals when a stop is selected ───────────────────────────────
  useEffect(() => {
    if (!selectedStop) return;
    setArrivals(null);
    setArrivalsLoading(true);
    transitGet(`/stops/${encodeURIComponent(selectedStop.id)}/arrivals`)
      .then(setArrivals)
      .catch(() => setArrivals({ arrivals: [], warnings: ['Could not load arrivals.'] }))
      .finally(() => setArrivalsLoading(false));
  }, [selectedStop]);

  const displayed = wheelchairOnly
    ? stops.filter(s => s.routeNumbers.length > 0) // GTFS stops don't carry wheelchair flag; show all with routes
    : stops;

  return (
    <div className="page-panel">
      <h2>📍 Nearby Bus Stops</h2>

      {gpsError && (
        <div className="banner banner-error" style={{ position: 'static', marginBottom: 12 }}>
          {gpsError}
        </div>
      )}

      {/* Controls */}
      <div className="inline-form" style={{ gap: 10, flexWrap: 'wrap', marginBottom: 12 }}>
        <label style={{ fontWeight: 600, fontSize: 14 }}>
          Radius:{' '}
          <select
            value={radius}
            onChange={e => setRadius(Number(e.target.value))}
            style={{ padding: '5px 10px', borderRadius: 8, border: '1px solid #ddd' }}
          >
            {RADIUS_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </label>
        <label style={{ fontSize: 14, display: 'flex', alignItems: 'center', gap: 5 }}>
          <input
            type="checkbox"
            checked={wheelchairOnly}
            onChange={e => setWheelchairOnly(e.target.checked)}
          />
          Routes only (hide empty stops)
        </label>
        <button onClick={fetchStops} disabled={!userLocation || loading}>
          ↻ Refresh
        </button>
      </div>

      {/* GPS accuracy hint */}
      {userLocation?.accuracy > 50 && (
        <p style={{ fontSize: 12, color: '#c58414', margin: '0 0 8px' }}>
          ⚠️ GPS accuracy ±{Math.round(userLocation.accuracy)} m — distances are approximate.
        </p>
      )}

      {loading && <p role="status">Searching for bus stops…</p>}
      {error   && <p role="alert" style={{ color: '#c0392b' }}>{error}</p>}

      {!loading && !error && displayed.length === 0 && userLocation && (
        <p>No bus stops found within {distLabel(radius)}. Try a larger radius.</p>
      )}
      {!userLocation && !gpsError && (
        <p role="status">Waiting for GPS location…</p>
      )}

      {/* Stop cards */}
      <ul className="list-cards" style={{ marginTop: 8 }}>
        {displayed.map(stop => {
          const isOpen = selectedStop?.id === stop.id;
          return (
            <li
              key={stop.id}
              className="card"
              style={{ borderLeft: isOpen ? '3px solid #4e8cff' : undefined }}
            >
              {/* Stop header — tap to expand arrivals */}
              <div
                className="card-row"
                onClick={() => setSelectedStop(isOpen ? null : stop)}
                style={{ cursor: 'pointer' }}
                role="button"
                tabIndex={0}
                aria-expanded={isOpen}
                onKeyDown={e => (e.key === 'Enter' || e.key === ' ') && setSelectedStop(isOpen ? null : stop)}
              >
                <div>
                  <strong style={{ fontSize: 14 }}>{stop.name}</strong>
                  <div style={{ fontSize: 12, color: '#555', marginTop: 2, display: 'flex', gap: 10 }}>
                    <span style={{ color: '#1a56db', fontWeight: 600 }}>📏 {distLabel(stop.distanceMeters)}</span>
                    <span style={{ color: '#16835b' }}>🚶 ~{Math.ceil(stop.walkMinutes)} min</span>
                    {feedAvailable && (
                      <span style={{
                        color: stop.liveCount > 0 ? '#065f46' : '#aaa',
                        fontWeight: stop.liveCount > 0 ? 700 : 400
                      }}>
                        🚍 {stop.liveCount > 0 ? `${stop.liveCount} approaching` : 'no live buses'}
                      </span>
                    )}
                  </div>
                  {/* Route chips */}
                  {stop.routeNumbers.length > 0 && (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3, marginTop: 5 }}>
                      {stop.routeNumbers.slice(0, 10).map(r => (
                        <span key={r} style={{
                          background: '#eef2ff', color: '#1a56db',
                          border: '1px solid #c7d2fe', borderRadius: 8,
                          padding: '1px 7px', fontSize: 11, fontWeight: 600
                        }}>{r}</span>
                      ))}
                      {stop.routeNumbers.length > 10 && (
                        <span style={{ fontSize: 11, color: '#888', alignSelf: 'center' }}>
                          +{stop.routeNumbers.length - 10} more
                        </span>
                      )}
                    </div>
                  )}
                  {stop.routeNumbers.length === 0 && (
                    <span style={{ fontSize: 11, color: '#aaa' }}>No routes mapped</span>
                  )}
                </div>
                <span aria-hidden="true" style={{ fontSize: 16, color: '#aaa' }}>
                  {isOpen ? '▲' : '▼'}
                </span>
              </div>

              {/* Arrivals panel */}
              {isOpen && (
                <div style={{ marginTop: 8, borderTop: '1px solid #eee', paddingTop: 8 }}>
                  {arrivalsLoading && <p role="status" style={{ fontSize: 13 }}>Loading arrivals…</p>}
                  {arrivals && (
                    <ArrivalCards
                      arrivals={arrivals.arrivals}
                      warnings={arrivals.warnings}
                      stopName={stop.name}
                      limit={5}
                    />
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
