/**
 * ArrivalCards — rich arrival prediction cards for a selected stop.
 * Replaces / enhances the inline arrival display in HomePage.
 *
 * Shows up to 5 arrivals with:
 * - Route number, direction, vehicle ID
 * - ETA (or "unavailable" — never fabricated)
 * - Prediction confidence (TRIP_UPDATE / GEOMETRY_ESTIMATE / UNAVAILABLE)
 * - Remaining stops when available
 * - Along-route distance
 * - Last GPS update age
 * - Notification toggle per arrival
 */
import useArrivalNotifications from '../hooks/useArrivalNotifications';

const CONFIDENCE_LABELS = {
  TRIP_UPDATE:       { label: 'Confirmed', color: '#16835b', icon: '✅' },
  GEOMETRY_ESTIMATE: { label: 'Estimated',  color: '#c58414', icon: '📍' },
  UNAVAILABLE:       { label: 'Unavailable', color: '#888',   icon: '⚠️' },
};

function ageLabel(epochSeconds) {
  if (!epochSeconds) return '';
  const s = Math.floor(Date.now() / 1000 - epochSeconds);
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  return `${Math.floor(s / 60)}m ${s % 60}s ago`;
}

export default function ArrivalCards({ arrivals = [], warnings = [], stopName, routeNumber, limit = 5 }) {
  const notif = useArrivalNotifications();

  // Trigger notifications on every render with fresh data
  notif.checkArrivals(arrivals, routeNumber, stopName);

  const displayed = arrivals.slice(0, limit);
  const conf = (a) => CONFIDENCE_LABELS[a.predictionAvailability] || CONFIDENCE_LABELS.UNAVAILABLE;

  return (
    <div className="arrival-section">
      {warnings.map(w => <p key={w} className="transit-warning">{w}</p>)}

      {/* Notification controls */}
      <div className="notif-controls">
        {notif.supported ? (
          notif.enabled ? (
            <span className="notif-on">
              🔔 Notifying at ≤
              <select
                value={notif.thresholdMinutes}
                onChange={e => notif.setThresholdMinutes(Number(e.target.value))}
                aria-label="Notification threshold"
                className="notif-threshold"
              >
                {[2, 3, 5, 7, 10].map(m => <option key={m} value={m}>{m} min</option>)}
              </select>
              <button className="notif-btn notif-off-btn" onClick={notif.disable}>Off</button>
            </span>
          ) : (
            <button className="notif-btn" onClick={notif.enable}>
              🔔 Notify when bus is close
            </button>
          )
        ) : null}
        {notif.enabled && (
          <small className="notif-disclaimer">Foreground only — notifications stop when tab closes</small>
        )}
      </div>

      {displayed.length === 0 && (
        <p className="arrival-empty">No verified approaching vehicles available.</p>
      )}

      {displayed.map(a => {
        const c = conf(a);
        return (
          <article className="arrival-card arrival-card-rich" key={`${a.vehicleId}-${a.tripId}`}>
            <div className="arrival-header">
              <span className="arrival-route">Route {a.routeNumber}</span>
              {a.etaMinutes != null ? (
                <span className="arrival-eta">{Math.ceil(a.etaMinutes)} min</span>
              ) : (
                <span className="arrival-eta arrival-eta-unknown">ETA unavailable</span>
              )}
            </div>
            <div className="arrival-meta">
              <span className="arrival-vehicle">Vehicle: {a.vehicleId}</span>
              {a.distanceMeters != null && (
                <span className="arrival-dist">{Math.round(a.distanceMeters)} m along route</span>
              )}
              {a.remainingStops != null && (
                <span className="arrival-stops">{a.remainingStops} stop{a.remainingStops !== 1 ? 's' : ''} away</span>
              )}
            </div>
            <div className="arrival-confidence" style={{ color: c.color }}>
              <span>{c.icon} {c.label}</span>
              <span className="arrival-gps"> · GPS: {ageLabel(a.lastGpsUpdate)}</span>
            </div>
            {a.reason && (
              <small className="arrival-reason">{a.reason}</small>
            )}
          </article>
        );
      })}
    </div>
  );
}
