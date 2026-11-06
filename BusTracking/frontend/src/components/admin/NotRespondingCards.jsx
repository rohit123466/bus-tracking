import { useCallback, useState } from 'react';
import { ErrorState, SkeletonList } from '../ui/States';
import { Tip } from './AdminWidgets';
import { useAdmin, useAdminPolling } from './adminContext';
import RouteDiagnosisDialog from './RouteDiagnosisDialog';
import { getNotRespondingRoutes } from '../../api/client';
import { routeLabel } from '../../utils/admin';
import { plural } from '../../utils/format';

const LIMIT = 8;

const HINT = 'Routes with buses that sent a GPS position earlier today (IST) but none in the last 5 minutes, '
  + 'most first. Shorter gaps are left out (buses often go 2–3 minutes between fixes), and so are buses not '
  + 'seen at all today: without a duty roster they are most likely off duty. '
  + 'Pick a route to see the likely reason for each bus.';

/** Overview panel: routes whose buses have gone quiet today. Picking one opens its diagnosis. */
export default function NotRespondingCards() {
  const { paused } = useAdmin();
  // While paused, fetch once and keep the list (usePolling keeps data for an unchanged key).
  const res = useAdminPolling('admin-not-responding', (signal) => getNotRespondingRoutes(LIMIT, signal),
    { interval: paused ? 0 : 30000 });
  const [selected, setSelected] = useState(null);
  const handleRouteClick = useCallback((route) => setSelected(route), []);
  const closeDiagnosis = useCallback(() => setSelected(null), []);

  const routes = res.data?.routes ?? [];
  const more = (res.data?.totalRoutes ?? 0) - routes.length;

  return (
    <section className="panel" aria-labelledby="ov-silent">
      <div className="panel-head">
        <h2 id="ov-silent">Not responding today <Tip text={HINT} label="About Not responding today" /></h2>
      </div>
      {!res.data && !res.error && <SkeletonList rows={3} label="Loading routes" />}
      {!res.data && res.error && <ErrorState error={res.error} onRetry={res.refresh} what="routes not responding" />}
      {res.data && routes.length === 0 && (
        <p className="panel-pad muted small">No bus has gone silent today.</p>
      )}
      {routes.length > 0 && (
        <ul className="list-reset bar-list">
          {routes.map((r) => {
            const { silentToday, assigned, reporting } = r.counts;
            return (
              <li key={r.routeCode}>
                <button type="button" className="bar-row bar-row-btn" aria-haspopup="dialog"
                  onClick={() => handleRouteClick(r)}>
                  <span className="route-chip">{routeLabel(r)}</span>
                  <span className="bar bar-danger" aria-hidden="true">
                    <span style={{ width: `${(silentToday / Math.max(1, assigned)) * 100}%` }} />
                  </span>
                  <span className="bar-num">
                    {silentToday}
                    <span className="vh">
                      {' '}of {plural(assigned, 'bus', 'buses')} silent today, {reporting} reporting. Show why
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {more > 0 && <p className="table-foot small muted">and {plural(more, 'more route')}</p>}
      {selected && <RouteDiagnosisDialog route={selected} onClose={closeDiagnosis} />}
    </section>
  );
}
