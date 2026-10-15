/**
 * NearestBusStand — automatically finds the closest GTFS bus stops
 * from the user's live GPS location.
 *
 * Business logic:
 *  1. As soon as a GPS fix arrives, calls GET /api/transit/stops/nearby
 *     which uses TransitIndex.nearby() (Haversine + spatial grid) to return
 *     stops sorted nearest-first with:
 *       - straight-line distance in metres
 *       - estimated walking time (5 km/h)
 *       - every public route number that serves the stop
 *       - count of live buses currently approaching the stop
 *  2. Polls every 15 s to keep the live bus count fresh.
 *  3. Tapping a stop selects it as the boarding stop (onSelectStop callback).
 *  4. Radius slider: 200 m – 2 km in four steps.
 *  5. Shows a "Locating you…" state while GPS is being acquired.
 *  6. Pins all found stops on the map via the onStopsFound callback.
 */
import { useEffect, useRef, useState } from 'react';
import { getNearbyGtfsStops, transitGet } from '../api/client';
import { isRegional, etaText, ETA_KIND } from '../utils/liveMap';
import './NearestBusStand.css';

const RADIUS_OPTIONS = [
  { value: 200,  label: '200 m' },
  { value: 500,  label: '500 m' },
  { value: 1000, label: '1 km'  },
  { value: 2000, label: '2 km'  },
];

function walkLabel(minutes) {
  if (minutes < 1) return '< 1 min walk';
  return `~${Math.ceil(minutes)} min walk`;
}

function distLabel(m) {
  if (m < 1000) return `${Math.round(m)} m`;
  return `${(m / 1000).toFixed(1)} km`;
}

export default function NearestBusStand({
  userLocation,       // { lat, lng, accuracy } from useUserLocation
  onSelectStop,       // (stop) => void  — called when user taps a stop
  onStopsFound,       // (stops) => void — to pin them on the map
  selectedStopId,     // currently selected stop id (for highlight)
  region,             // optional Live Map filter: only stops served by region-eligible routes
}) {
  const regional = isRegional(region);
  const [radius, setRadius]   = useState(1000);
  const [result, setResult]   = useState(null);   // NearbyStopsResult
  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState('');
  const abortRef              = useRef(null);
  const timerRef              = useRef(null);

  // ── Fetch whenever location or radius changes ────────────────────────────
  useEffect(() => {
    if (!userLocation) return;

    const fetchStops = async () => {
      // Cancel any in-flight request
      abortRef.current?.abort();
      const ctrl = new AbortController();
      abortRef.current = ctrl;
      setLoading(true);

      try {
        const data = regional
          ? await transitGet('/stops/nearby-regional', {
              latitude: userLocation.lat, longitude: userLocation.lng,
              radiusMeters: radius, limit: 8, region,
            }, ctrl.signal)
          : await getNearbyGtfsStops(userLocation.lat, userLocation.lng, radius, 8);
        if (ctrl.signal.aborted) return;
        setResult(data);
        setError('');
        // Notify parent so stops can be pinned on the map
        if (onStopsFound) onStopsFound(data.stops);
      } catch {
        if (ctrl.signal.aborted) return;
        setError('Could not find nearby stops. Check your connection.');
      } finally {
        if (!ctrl.signal.aborted) setLoading(false);
      }
    };

    fetchStops();
    // Refresh every 15 s to update live bus counts
    timerRef.current = setInterval(fetchStops, 15000);

    return () => {
      abortRef.current?.abort();
      clearInterval(timerRef.current);
    };
  }, [userLocation, radius, region]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── No GPS yet ───────────────────────────────────────────────────────────
  if (!userLocation) {
    return (
      <div className="nbs-panel">
        <div className="nbs-locating" role="status" aria-live="polite">
          <span className="nbs-pulse" aria-hidden="true">📍</span>
          <span>Locating you… please allow location access.</span>
        </div>
      </div>
    );
  }

  const stops = result?.stops ?? [];

  return (
    <div className="nbs-panel">
      {/* Header with radius control */}
      <div className="nbs-header">
        <span className="nbs-title">🚏 Nearest Bus Stands</span>
        <div className="nbs-radius-group" role="group" aria-label="Search radius">
          {RADIUS_OPTIONS.map(opt => (
            <button
              key={opt.value}
              className={'nbs-radius-btn' + (radius === opt.value ? ' nbs-radius-active' : '')}
              onClick={() => setRadius(opt.value)}
              aria-pressed={radius === opt.value}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* GPS accuracy hint */}
      {userLocation.accuracy > 50 && (
        <p className="nbs-accuracy-warn" role="alert">
          ⚠️ GPS accuracy is ±{Math.round(userLocation.accuracy)} m — distances are approximate.
        </p>
      )}

      {/* Loading / error states */}
      {loading && stops.length === 0 && (
        <p className="nbs-loading" role="status">Searching for bus stops…</p>
      )}
      {error && <p className="nbs-error" role="alert">{error}</p>}

      {/* No stops in radius */}
      {!loading && stops.length === 0 && !error && (
        <p className="nbs-empty">
          No bus stands found within {distLabel(radius)}. Try a larger radius.
        </p>
      )}

      {/* Stop list */}
      {stops.length > 0 && (
        <ul className="nbs-list" aria-label="Nearby bus stands">
          {stops.map((stop, idx) => {
            const isSelected = stop.id === selectedStopId;
            return (
              <li
                key={stop.id}
                className={'nbs-item' + (isSelected ? ' nbs-item-selected' : '')}
                onClick={() => onSelectStop?.(stop)}
                onKeyDown={e => (e.key === 'Enter' || e.key === ' ') && onSelectStop?.(stop)}
                tabIndex={0}
                role="button"
                aria-pressed={isSelected}
                aria-label={`${stop.name}, ${distLabel(stop.distanceMeters)} away, ${walkLabel(stop.walkMinutes)}`}
              >
                {/* Rank badge */}
                <span className="nbs-rank" aria-hidden="true">{idx + 1}</span>

                {/* Main info */}
                <div className="nbs-info">
                  <span className="nbs-name">{stop.name}</span>
                  <div className="nbs-meta">
                    <span className="nbs-dist" title="Straight-line distance">
                      📏 {distLabel(stop.distanceMeters)}
                    </span>
                    <span className="nbs-walk" title={regional
                      ? 'Straight-line estimate at 5 km/h, not a road route. Select the stop and use "Walk to" for a walking route.'
                      : 'Estimated walking time at 5 km/h'}>
                      🚶 {walkLabel(stop.walkMinutes)}{regional && ' (straight-line est.)'}
                    </span>
                    {/* Live bus count badge */}
                    {result?.feedAvailable && (
                      <span
                        className={'nbs-live' + (stop.liveCount > 0 ? ' nbs-live-active' : '')}
                        title={`${stop.liveCount} bus${stop.liveCount !== 1 ? 'es' : ''} currently approaching`}
                      >
                        🚍 {stop.liveCount > 0 ? stop.liveCount : '—'}
                      </span>
                    )}
                  </div>

                  {/* Route chips */}
                  {stop.routeNumbers.length > 0 && (
                    <div className="nbs-routes" aria-label="Routes at this stop">
                      {stop.routeNumbers.slice(0, 8).map(r => (
                        <span key={r} className="nbs-route-chip">{r}</span>
                      ))}
                      {stop.routeNumbers.length > 8 && (
                        <span className="nbs-route-more">+{stop.routeNumbers.length - 8} more</span>
                      )}
                    </div>
                  )}

                  {/* Live Map regional mode: upcoming buses on eligible routes */}
                  {regional && stop.upcoming && (
                    stop.upcoming.length > 0 ? (
                      <ul className="nbs-upcoming" aria-label="Upcoming buses">
                        {stop.upcoming.map(u => (
                          <li key={u.vehicleId} title={u.reason ? `${u.label}. ${u.reason}` : u.label}>
                            <span className="nbs-route-chip">{u.routeNumber}</span>
                            {u.remainingStops != null && <span>{u.remainingStops} stops away</span>}
                            <span className={`nbs-eta nbs-eta-${ETA_KIND[u.availability] ?? 'unavailable'}`}>
                              {etaText(u)}
                            </span>
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <small style={{ color: '#888' }}>
                        {result?.feedAvailable
                          ? 'No live bus on these routes is currently tracked towards this stop.'
                          : 'Live vehicle feed unavailable: showing static route data only.'}
                      </small>
                    )
                  )}
                </div>

                {/* Select chevron */}
                <span className="nbs-chevron" aria-hidden="true">›</span>
              </li>
            );
          })}
        </ul>
      )}

      {/* Refresh indicator */}
      {result && (
        <p className="nbs-footer">
          {loading ? '↻ Updating…' : `${stops.length} stop${stops.length !== 1 ? 's' : ''} within ${distLabel(radius)}`}
          {!result.feedAvailable && (
            <span className="nbs-feed-warn"> · Live counts unavailable</span>
          )}
        </p>
      )}
    </div>
  );
}
