/**
 * JourneyPlannerPage — Smart Journey Planner with three preferences (Feature 5).
 *
 * Preferences:
 *  FASTEST          — minimize total journey minutes
 *  FEWEST_TRANSFERS — prefer direct routes
 *  MINIMUM_WALKING  — minimize total walking distance
 *
 * Bus + Metro multimodal journeys are BLOCKED pending an authoritative DMRC
 * data source. The backend MetroAdapter abstraction is in place; this notice
 * will be removed when a verified source is configured.
 */
import { useState } from 'react';
import { getNearbyStops, planJourneyWithPreference } from '../api/client';
import { haversineMeters } from '../utils/geo';
import PlaceSearchField from '../components/PlaceSearchField';

const NEAREST_STOP_RADIUS_METERS = 2000;

const PREFERENCES = [
  { value: 'FASTEST',          label: '⚡ Fastest Journey' },
  { value: 'FEWEST_TRANSFERS', label: '🔄 Fewest Transfers' },
  { value: 'MINIMUM_WALKING',  label: '🚶 Minimum Walking' },
];

function usePlaceEndpoint() {
  const [query, setQuery] = useState('');
  const [place, setPlace] = useState(null);
  const [nearestStop, setNearestStop] = useState(null);
  const [findingStop, setFindingStop] = useState(false);

  const resolve = (resolvedPlace) => {
    setPlace(resolvedPlace);
    setQuery(resolvedPlace.label);
    setNearestStop(null);
    setFindingStop(true);
    getNearbyStops(resolvedPlace.lat, resolvedPlace.lng, NEAREST_STOP_RADIUS_METERS, 1)
      .then((stops) => {
        if (!stops.length) { setNearestStop(null); return; }
        const stop = stops[0];
        setNearestStop({
          name: stop.name,
          distanceMeters: haversineMeters(resolvedPlace.lat, resolvedPlace.lng, stop.latitude, stop.longitude),
        });
      })
      .catch(() => setNearestStop(null))
      .finally(() => setFindingStop(false));
  };

  const reset = () => { setPlace(null); setNearestStop(null); };

  return { query, setQuery, place, nearestStop, findingStop, resolve, reset };
}

export default function JourneyPlannerPage() {
  const source = usePlaceEndpoint();
  const destination = usePlaceEndpoint();
  const [preference, setPreference] = useState('FASTEST');
  const [options, setOptions] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const useMyLocation = () => {
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition((pos) => {
      source.resolve({
        label: 'My current location',
        lat: pos.coords.latitude,
        lng: pos.coords.longitude,
      });
    });
  };

  const submit = (e) => {
    e.preventDefault();
    if (!source.place || !destination.place) return;
    setError(null);
    setLoading(true);
    planJourneyWithPreference(
      source.place.lat, source.place.lng,
      destination.place.lat, destination.place.lng,
      preference
    )
      .then((res) => setOptions(res.options))
      .catch(() => setError('Could not plan a journey between those points.'))
      .finally(() => setLoading(false));
  };

  return (
    <div className="page-panel">
      <h2>Smart Journey Planner</h2>

      {/* Metro blocked notice */}
      <div className="banner" style={{
        background: '#fffbe6', border: '1px solid #f9d45a',
        borderRadius: 8, padding: '8px 12px', marginBottom: 12, fontSize: 13
      }}>
        🚇 <strong>Bus + Metro journeys:</strong> DMRC metro data is not available through an
        authoritative open feed. Bus-only journey planning works fully.
      </div>

      <form className="journey-form" onSubmit={submit}>
        <fieldset>
          <legend>Source</legend>
          <PlaceSearchField
            placeholder="Search a place..."
            value={source.query}
            onChange={(q) => { source.setQuery(q); source.reset(); }}
            onSelect={source.resolve}
          />
          <button type="button" onClick={useMyLocation}>Use my location</button>
        </fieldset>
        <NearestStopHint endpoint={source} />

        <fieldset>
          <legend>Destination</legend>
          <PlaceSearchField
            placeholder="Search a place..."
            value={destination.query}
            onChange={(q) => { destination.setQuery(q); destination.reset(); }}
            onSelect={destination.resolve}
          />
        </fieldset>
        <NearestStopHint endpoint={destination} />

        {/* Journey preference */}
        <fieldset>
          <legend>Preference</legend>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {PREFERENCES.map(p => (
              <label key={p.value} style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer', fontSize: 14 }}>
                <input
                  type="radio"
                  name="preference"
                  value={p.value}
                  checked={preference === p.value}
                  onChange={() => setPreference(p.value)}
                />
                {p.label}
              </label>
            ))}
          </div>
        </fieldset>

        <button type="submit" disabled={loading || !source.place || !destination.place}>
          {loading ? 'Planning…' : 'Plan Journey'}
        </button>
      </form>

      {error && (
        <div className="banner banner-error" style={{ position: 'static', marginTop: 12 }}>
          {error}
        </div>
      )}

      {options && options.length === 0 && (
        <p style={{ marginTop: 16 }}>No route connects those two points yet.</p>
      )}

      <div className="list-cards" style={{ marginTop: 16 }}>
        {options?.map((option, i) => (
          <div key={i} className="card">
            <div className="card-row">
              <strong>{option.direct ? 'Direct' : `Transfer (${option.legs.length} legs)`}</strong>
              <span>{option.totalMinutes} min total</span>
            </div>
            <p>Walk to boarding stop: {Math.round(option.walkToBoardMeters)} m</p>
            {option.legs.map((leg, j) => (
              <div key={j} className="card-row">
                <span>
                  Route {leg.routeCode}{leg.routeName ? ` — ${leg.routeName}` : ''}: {leg.boardStopName} → {leg.alightStopName}
                </span>
                <span>{leg.inVehicleMinutes} min</span>
              </div>
            ))}
            <p>Walk from alighting stop: {Math.round(option.walkFromAlightMeters)} m</p>
            {/* Fare information is in the existing fare calculator; journey-level fare is a future feature. */}
          </div>
        ))}
      </div>
    </div>
  );
}

function NearestStopHint({ endpoint }) {
  if (!endpoint.place) return null;
  if (endpoint.findingStop) return <p className="nearest-stop-hint">Finding nearest bus stop…</p>;
  if (!endpoint.nearestStop) return (
    <p className="nearest-stop-hint">No bus stop within {NEAREST_STOP_RADIUS_METERS / 1000} km.</p>
  );
  return (
    <p className="nearest-stop-hint">
      Nearest bus stop: <strong>{endpoint.nearestStop.name}</strong>{' '}
      ({Math.round(endpoint.nearestStop.distanceMeters)} m away)
    </p>
  );
}
