import { useMemo, useState } from 'react';
import Icon from '../../ui/Icon';
import { EmptyState, ErrorState, SkeletonList } from '../../ui/States';
import ServiceAlertsPanel from '../../ServiceAlertsPanel';
import { UpdatedAgo } from '../AdminWidgets';
import { useAdmin } from '../adminContext';
import { countBySeverity, SEVERITIES } from '../../../utils/admin';

const SEVERITY_LABEL = { CRITICAL: 'Critical', WARNING: 'Warning', INFO: 'Info' };
const FEED_STATUS_TEXT = { OK: 'Live feed OK', STALE: 'Live feed is old', UNAVAILABLE: 'Live feed unavailable' };

/** Service alerts from GET /transit/alerts, rendered by the same panel riders see (demo badge included). */
export default function AlertsSection() {
  const { alerts } = useAdmin();
  const [only, setOnly] = useState(() => new Set());
  const list = useMemo(() => alerts.data?.alerts ?? [], [alerts.data]);
  const counts = countBySeverity(list);
  const filtered = only.size ? list.filter((a) => only.has(a.severity)) : list;

  const toggle = (s) => setOnly((prev) => {
    const next = new Set(prev);
    if (next.has(s)) next.delete(s); else next.add(s);
    return next;
  });

  return (
    <div className="admin-pad stack">
      <div className="admin-toolbar">
        <div className="filter-group" role="group" aria-label="Filter by severity">
          <button type="button" className="filter-btn" aria-pressed={only.size === 0} onClick={() => setOnly(new Set())}>
            All <strong>{list.length}</strong>
          </button>
          {SEVERITIES.map((s) => (
            <button key={s} type="button" className={`filter-btn filter-sev-${s.toLowerCase()}`} aria-pressed={only.has(s)} onClick={() => toggle(s)}>
              {SEVERITY_LABEL[s]} <strong>{counts[s]}</strong>
            </button>
          ))}
        </div>
        <div className="admin-toolbar-end">
          {alerts.data?.feedStatus && <span className="tag">{FEED_STATUS_TEXT[alerts.data.feedStatus] ?? alerts.data.feedStatus}</span>}
          <UpdatedAgo updatedAt={alerts.updatedAt} error={alerts.error} staleAfterMs={90000} />
          <button type="button" className="btn btn-sm" onClick={alerts.refresh}><Icon name="refresh" size={16} />Refresh</button>
        </div>
      </div>

      {!alerts.data && alerts.loading && <SkeletonList rows={3} label="Loading alerts" />}
      {!alerts.data && alerts.error && <div className="panel"><ErrorState error={alerts.error} onRetry={alerts.refresh} what="service alerts" /></div>}
      {alerts.data && list.length === 0 && (
        <div className="panel"><EmptyState icon="check" title="No active alerts">Feed problems, cancellations and stale data would show here.</EmptyState></div>
      )}
      {alerts.data && list.length > 0 && filtered.length === 0 && (
        <div className="panel"><EmptyState icon="info" title="Nothing at this severity">Clear the filter to see all {list.length} alerts.</EmptyState></div>
      )}
      <ServiceAlertsPanel alertsResult={{ alerts: filtered }} />
      <p className="small muted">Refreshed every minute. Alerts come from the GTFS-realtime feed and the server’s own feed checks.</p>
    </div>
  );
}
