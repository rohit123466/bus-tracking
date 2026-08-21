import { useEffect, useState } from 'react';
import { MapContainer, TileLayer, Marker, Polyline, Popup } from 'react-leaflet';
import { getRouteDetail, searchRoutes } from '../api/client';
import { busIcon, stopIconFor } from '../utils/leafletIcons';
import StopProgressList from '../components/StopProgressList';
import AccessibilityInfo from '../components/AccessibilityInfo';

const DELHI_CENTER = [28.6139, 77.209];

export default function RouteDetailPage() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [routeId, setRouteId] = useState(null);
  const [vehicleId, setVehicleId] = useState('');
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      return;
    }
    const handle = setTimeout(() => {
      searchRoutes(query).then(setResults).catch(() => setResults([]));
    }, 300);
    return () => clearTimeout(handle);
  }, [query]);

  const loadDetail = (id, vId) => {
    setError(null);
    getRouteDetail(id, vId || undefined)
      .then(setDetail)
      .catch((err) => setError(err.response?.data?.message || 'Failed to load route detail.'));
  };

  useEffect(() => {
    if (routeId) loadDetail(routeId, vehicleId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeId]);

  const polyline = detail?.stops?.map((s) => [s.latitude, s.longitude]) || [];

  return (
    <div className="page-panel page-panel-wide">
      <h2>Bus Route</h2>
      <div className="inline-form">
        <input
          type="text"
          placeholder="Search route number or name…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {results.length > 0 && !routeId && (
        <ul className="search-results" style={{ position: 'static', marginTop: 8 }}>
          {results.map((route) => (
            <li
              key={route.id}
              onClick={() => {
                setRouteId(route.id);
                setQuery(`${route.routeCode} — ${route.name || ''}`);
                setResults([]);
              }}
            >
              <strong>{route.routeCode}</strong> {route.name}
            </li>
          ))}
        </ul>
      )}

      {routeId && (
        <>
          <form
            className="inline-form"
            style={{ marginTop: 12 }}
            onSubmit={(e) => {
              e.preventDefault();
              loadDetail(routeId, vehicleId);
            }}
          >
            <input
              type="text"
              placeholder="Vehicle ID to highlight progress (optional)"
              value={vehicleId}
              onChange={(e) => setVehicleId(e.target.value)}
            />
            <button type="submit">Track</button>
            <button
              type="button"
              onClick={() => {
                setRouteId(null);
                setDetail(null);
                setQuery('');
                setVehicleId('');
              }}
            >
              Clear
            </button>
          </form>

          {error && <div className="banner banner-error" style={{ position: 'static', marginTop: 12 }}>{error}</div>}

          {detail && (
            <div className="route-detail-layout">
              <div className="card">
                <h3>
                  Route {detail.routeCode}
                  {detail.name ? ` — ${detail.name}` : ''}
                  {detail.wheelchairAccessible && (
                    <span role="img" aria-label="Wheelchair accessible" title="Wheelchair accessible" style={{ marginLeft: 8 }}>
                      ♿
                    </span>
                  )}
                </h3>
                <AccessibilityInfo accessible={detail.wheelchairAccessible} spaces={detail.wheelchairSpaces} />
                {detail.etaToNextStopMinutes != null && (
                  <p>ETA to next stop: {detail.etaToNextStopMinutes} min</p>
                )}
                <StopProgressList stops={detail.stops} />
              </div>

              <div className="mini-map">
                <MapContainer center={DELHI_CENTER} zoom={12} style={{ height: '100%', width: '100%' }}>
                  <TileLayer
                    attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                    url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                  />
                  {polyline.length > 0 && <Polyline positions={polyline} pathOptions={{ color: '#aa3bff', weight: 4 }} />}
                  {detail.stops.map((s) => (
                    <Marker key={s.stopId} position={[s.latitude, s.longitude]} icon={stopIconFor(s.name)}>
                      <Popup>
                        {s.sequence}. {s.name}
                      </Popup>
                    </Marker>
                  ))}
                  {detail.vehicleLatitude != null && (
                    <Marker position={[detail.vehicleLatitude, detail.vehicleLongitude]} icon={busIcon(detail.routeCode)}>
                      <Popup>Vehicle {detail.trackedVehicleId}</Popup>
                    </Marker>
                  )}
                </MapContainer>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
