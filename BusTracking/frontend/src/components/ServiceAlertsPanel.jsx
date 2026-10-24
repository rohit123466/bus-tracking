/**
 * ServiceAlertsPanel — live service alerts for the current route/feed, from GET /api/transit/alerts:
 * feed outages, cancellations and stale-data warnings. Renders nothing when there are none.
 */
import Icon from './ui/Icon';

const TYPE_LABEL = {
  FEED_UNAVAILABLE: 'Live feed unavailable',
  FEED_STALE: 'Live data is old',
  CANCELLATION: 'Cancelled trip',
  ROUTE_DIVERSION: 'Route diversion',
  STOP_CLOSURE: 'Stop closed',
  DELAY: 'Significant delay',
};

export default function ServiceAlertsPanel({ alertsResult }) {
  const alerts = alertsResult?.alerts ?? [];
  if (!alerts.length) return null;
  return (
    <section className="alert-list" aria-label="Service alerts">
      {alerts.map((a, i) => (
        <div key={`${a.type}-${a.tripId ?? ''}-${i}`} className={`alert-card alert-${a.severity?.toLowerCase()}`}>
          <Icon name={a.severity === 'INFO' ? 'info' : 'alert'} size={18} />
          <div className="alert-body">
            <strong>{TYPE_LABEL[a.type] || a.type}</strong>
            {a.affectedRoute && <span> · Route {a.affectedRoute}</span>}
            <p>{a.message}</p>
            {a.demo && <small className="alert-demo">Demo alert — not an official DTC notification</small>}
          </div>
        </div>
      ))}
    </section>
  );
}
