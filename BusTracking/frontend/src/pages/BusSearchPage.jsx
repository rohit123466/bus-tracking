import { useEffect, useRef, useState } from 'react';
import { getBusByVehicleId } from '../api/client';
import StopProgressList from '../components/StopProgressList';
import AccessibilityInfo from '../components/AccessibilityInfo';

export default function BusSearchPage() {
  const [vehicleId, setVehicleId] = useState('');
  const [tracked, setTracked] = useState(null);
  const [bus, setBus] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [wheelchairOnly, setWheelchairOnly] = useState(false);
  const pollRef = useRef(null);

  const search = (id) => {
    if (!id.trim()) return;
    setLoading(true);
    setError(null);
    getBusByVehicleId(id.trim())
      .then((data) => {
        setBus(data);
        setTracked(id.trim());
      })
      .catch((err) => {
        setError(err.response?.data?.message || 'Bus not found or has no live position.');
        setBus(null);
      })
      .finally(() => setLoading(false));
  };

  // Re-poll the tracked bus every 8s so the page shows near-live movement.
  useEffect(() => {
    if (!tracked) return undefined;
    pollRef.current = setInterval(() => search(tracked), 8000);
    return () => clearInterval(pollRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tracked]);

  return (
    <div className="page-panel">
      <h2>Search by Bus Number</h2>
      <form
        className="inline-form"
        onSubmit={(e) => {
          e.preventDefault();
          search(vehicleId);
        }}
      >
        <input
          type="text"
          placeholder="Vehicle ID, e.g. DL1PD6882"
          value={vehicleId}
          onChange={(e) => setVehicleId(e.target.value)}
        />
        <button type="submit" disabled={loading}>
          {loading ? 'Searching…' : 'Search'}
        </button>
        <label>
          <input
            type="checkbox"
            checked={wheelchairOnly}
            onChange={(e) => setWheelchairOnly(e.target.checked)}
          />
          Wheelchair accessible only
        </label>
      </form>

      {error && <div className="banner banner-error" style={{ position: 'static', marginTop: 12 }}>{error}</div>}

      {bus && wheelchairOnly && !bus.wheelchairAccessible && (
        <p style={{ marginTop: 12 }}>
          Bus {bus.vehicleId} isn't marked wheelchair-accessible. Uncheck "Wheelchair accessible only" to see it anyway.
        </p>
      )}

      {bus && (!wheelchairOnly || bus.wheelchairAccessible) && (
        <div className="card" style={{ marginTop: 16 }}>
          <h3>
            {bus.vehicleId} — Route {bus.routeCode}
            {bus.routeName ? ` (${bus.routeName})` : ''}
            {bus.wheelchairAccessible && (
              <span role="img" aria-label="Wheelchair accessible" title="Wheelchair accessible" style={{ marginLeft: 8 }}>
                ♿
              </span>
            )}
          </h3>
          <AccessibilityInfo accessible={bus.wheelchairAccessible} spaces={bus.wheelchairSpaces} />
          <div className="kv-grid">
            <div>Speed</div>
            <div>{bus.speedKmh != null ? `${bus.speedKmh.toFixed(1)} km/h` : 'Unknown'}</div>
            <div>Direction</div>
            <div>{bus.bearing != null ? `${Math.round(bus.bearing)}°` : 'Unknown'}</div>
            <div>Last updated</div>
            <div>{bus.lastUpdatedEpochSeconds ? new Date(bus.lastUpdatedEpochSeconds * 1000).toLocaleTimeString() : 'Unknown'}</div>
            <div>Position</div>
            <div>
              {bus.latitude?.toFixed(5)}, {bus.longitude?.toFixed(5)}
            </div>
            {bus.route?.etaToNextStopMinutes != null && (
              <>
                <div>ETA to next stop</div>
                <div>{bus.route.etaToNextStopMinutes} min</div>
              </>
            )}
          </div>

          {bus.route ? (
            <>
              <h4 style={{ marginTop: 16 }}>Route: {bus.route.routeCode}</h4>
              <StopProgressList stops={bus.route.stops} />
            </>
          ) : (
            <p>This vehicle isn't matched to a known GTFS route yet.</p>
          )}
        </div>
      )}
    </div>
  );
}
