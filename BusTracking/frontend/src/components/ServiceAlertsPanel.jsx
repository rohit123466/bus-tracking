/**
 * ServiceAlertsPanel — displays live service alerts for the current route/feed.
 * Sourced from GET /api/transit/alerts. Shows feed errors, cancellations, and
 * stale-data warnings. Separates system warnings from passenger-facing alerts.
 */
const SEVERITY_ICON = { CRITICAL: '🔴', WARNING: '🟡', INFO: 'ℹ️' };
const TYPE_LABEL = {
  FEED_UNAVAILABLE: 'Feed Unavailable',
  FEED_STALE: 'Stale Data',
  CANCELLATION: 'Cancelled Trip',
  ROUTE_DIVERSION: 'Route Diversion',
  STOP_CLOSURE: 'Stop Closed',
  DELAY: 'Significant Delay',
};

export default function ServiceAlertsPanel({ alertsResult }) {
  if (!alertsResult) return null;
  const { alerts = [], feedStatus } = alertsResult;
  if (alerts.length === 0 && feedStatus === 'OK') return null;

  return (
    <div className="alerts-panel" role="region" aria-label="Service alerts">
      {alerts.map((a, i) => (
        <div
          key={i}
          className={`alert-card alert-${a.severity.toLowerCase()}`}
          role="alert"
        >
          <span className="alert-icon">{SEVERITY_ICON[a.severity] || 'ℹ️'}</span>
          <div className="alert-body">
            <strong>{TYPE_LABEL[a.type] || a.type}</strong>
            {a.affectedRoute && <span className="alert-route"> · Route {a.affectedRoute}</span>}
            <p>{a.message}</p>
            {a.demo && <small className="alert-demo">Demo alert — not an official DTC notification</small>}
          </div>
        </div>
      ))}
    </div>
  );
}
