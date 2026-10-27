import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../../ui/Icon';
import { ErrorState, Notice } from '../../ui/States';
import Sparkline from '../Sparkline';
import { Tip, UpdatedAgo } from '../AdminWidgets';
import { sectionPath, useAdmin } from '../adminContext';
import { useNow } from '../../../hooks/useStores';
import { countStatuses, routeLabel, STATUS_META, trend } from '../../../utils/admin';
import { ageText } from '../../../utils/format';

// `better`: which direction of change is good news, for the colour of the change indicator.
const KPIS = [
  {
    field: 'live', stat: 'liveBusCount', label: 'Reporting now', better: 'up',
    hint: 'Buses with a GPS position from the last 2 minutes.',
  },
  {
    field: 'slow', stat: 'delayedBusCount', label: 'Slow / possibly stuck', better: 'down',
    hint: STATUS_META.SLOW.hint,
  },
  {
    field: 'offline', stat: 'offlineBusCount', label: 'Not reporting', better: 'down',
    hint: 'Registered buses (every vehicle seen since the database was created) with no position in the last 2 minutes. '
      + 'Includes buses that are simply off duty.',
  },
  {
    field: 'averageSpeedKmh', stat: 'averageSpeedKmh', label: 'Average speed', unit: 'km/h', better: 'up',
    hint: 'Mean speed of the reporting buses that report a speed.',
  },
];

function Delta({ change, better, unit }) {
  if (!change) return <span className="delta small muted">No trend yet</span>;
  const minutes = Math.max(1, Math.round(change.sinceMs / 60000));
  const dir = change.delta > 0 ? 'up' : change.delta < 0 ? 'down' : 'flat';
  const tone = dir === 'flat' ? '' : dir === better ? ' delta-good' : ' delta-bad';
  const amount = `${change.delta > 0 ? '+' : ''}${change.delta}${unit ? ` ${unit}` : ''}`;
  return (
    <span className={`delta small${tone}`}>
      {dir !== 'flat' && <Icon name={dir === 'up' ? 'chevronUp' : 'chevronDown'} size={14} />}
      <span>{dir === 'flat' ? 'No change' : amount} <span className="muted">in {minutes} min</span></span>
    </span>
  );
}

function KpiCard({ kpi, stats, history }) {
  const value = stats[kpi.stat];
  const change = trend(history, kpi.field);
  const series = history.map((s) => s[kpi.field]).filter((v) => v != null);
  const label = series.length > 1
    ? `${kpi.label} over the last ${Math.max(1, Math.round((history[history.length - 1].at - history[0].at) / 60000))} min: from ${series[0]} to ${series[series.length - 1]}`
    : `${kpi.label}: not enough samples for a trend yet`;
  return (
    <article className="panel admin-kpi">
      <h2 className="admin-kpi-label">
        {kpi.label}
        <Tip text={kpi.hint} label={`About ${kpi.label}`} />
      </h2>
      <div className="admin-kpi-row">
        <span className="admin-kpi-num">{value}{kpi.unit && <small> {kpi.unit}</small>}</span>
        <Delta change={change} better={kpi.better} unit={kpi.unit} />
      </div>
      <Sparkline samples={history} field={kpi.field} label={label} width={160} height={40} />
    </article>
  );
}

function KpiSkeleton() {
  return (
    <div className="admin-kpis" role="status" aria-label="Loading figures">
      {KPIS.map((k) => (
        <div key={k.field} className="panel admin-kpi">
          <span className="sk" style={{ width: '55%', height: 14 }} />
          <span className="sk" style={{ width: '35%', height: 34 }} />
          <span className="sk" style={{ width: '100%', height: 40 }} />
        </div>
      ))}
    </div>
  );
}

export default function OverviewSection() {
  const { stats, statsUpdatedAt, statsError, refreshStats, paused, setPaused, history, positions } = useAdmin();
  const now = useNow(5000);
  const mix = useMemo(() => countStatuses(positions, { now }), [positions, now]);
  const mixTotal = mix.MOVING + mix.SLOW + mix.STALE;
  const top = (stats?.routeStats ?? []).slice(0, 6);
  const topMax = Math.max(1, ...top.map((r) => r.activeBusCount));

  return (
    <div className="admin-pad stack">
      <div className="admin-toolbar">
        <UpdatedAgo updatedAt={statsUpdatedAt} error={statsError} paused={paused} />
        <div className="admin-toolbar-end">
          <button type="button" className="btn btn-sm" aria-pressed={paused} onClick={() => setPaused(!paused)}>
            <Icon name={paused ? 'play' : 'pause'} size={16} />{paused ? 'Resume' : 'Pause'} auto-refresh
          </button>
          <button type="button" className="btn btn-sm" onClick={refreshStats} disabled={paused}
            title={paused ? 'Resume auto-refresh to fetch new numbers' : 'Fetch the latest numbers now'}>
            <Icon name="refresh" size={16} />Refresh
          </button>
        </div>
      </div>

      {!stats && !statsError && <KpiSkeleton />}
      {!stats && statsError && <div className="panel"><ErrorState error={statsError} onRetry={refreshStats} what="the admin figures" /></div>}
      {stats && statsError && (
        <Notice tone="warn" role="status">
          Showing figures from {ageText(statsUpdatedAt / 1000)}. {statsError.message}
        </Notice>
      )}

      {stats && (
        <>
          <div className="admin-kpis">
            {KPIS.map((k) => <KpiCard key={k.field} kpi={k} stats={stats} history={history} />)}
          </div>

          <div className="admin-cols">
            <section className="panel" aria-labelledby="ov-routes">
              <div className="panel-head">
                <h2 id="ov-routes">Busiest routes</h2>
                <Link className="btn btn-sm btn-ghost" to={sectionPath('routes')}>All routes<Icon name="chevronRight" size={16} /></Link>
              </div>
              {top.length === 0 ? (
                <p className="panel-pad muted small">No routes are reporting live buses right now.</p>
              ) : (
                <ul className="list-reset bar-list">
                  {top.map((r) => (
                    <li key={r.routeCode}>
                      <Link className="bar-row" to={sectionPath('map', { route: r.routeCode })}>
                        <span className="route-chip">{routeLabel(r)}</span>
                        <span className="bar" aria-hidden="true"><span style={{ width: `${(r.activeBusCount / topMax) * 100}%` }} /></span>
                        <span className="bar-num">{r.activeBusCount}<span className="vh"> buses reporting. Show on map</span></span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </section>

            <section className="panel" aria-labelledby="ov-mix">
              <div className="panel-head">
                <h2 id="ov-mix">Fleet on the map</h2>
                <Link className="btn btn-sm btn-ghost" to={sectionPath('map')}>Live map<Icon name="chevronRight" size={16} /></Link>
              </div>
              <div className="panel-pad stack">
                {mixTotal === 0 ? (
                  <p className="muted small">No bus positions received yet.</p>
                ) : (
                  <>
                    <div className="stacked" role="img"
                      aria-label={`${mix.MOVING} moving, ${mix.SLOW} slow or possibly stuck, ${mix.STALE} stale`}>
                      {['MOVING', 'SLOW', 'STALE'].map((s) => mix[s] > 0 && (
                        <span key={s} className={`stacked-${STATUS_META[s].tone}`} style={{ flexGrow: mix[s] }} />
                      ))}
                    </div>
                    <ul className="list-reset mix-list">
                      {['MOVING', 'SLOW', 'STALE'].map((s) => (
                        <li key={s}>
                          <span className={`status-swatch status-swatch-${STATUS_META[s].tone}`} aria-hidden="true" />
                          {STATUS_META[s].label}<strong>{mix[s]}</strong>
                        </li>
                      ))}
                    </ul>
                  </>
                )}
                <p className="small muted">
                  From live WebSocket pushes and the fleet snapshot. “Slow” is a speed reading, not a timetable delay.
                </p>
              </div>
            </section>

            <section className="panel" aria-labelledby="ov-tools">
              <div className="panel-head"><h2 id="ov-tools">Other tools</h2></div>
              <ul className="list-reset stack admin-links">
                <li><Link to="/conductor">Conductor console</Link> <span className="muted small">— ticket sales, seat map, camera checks (demo data)</span></li>
                <li><Link to="/replay">Journey replay</Link> <span className="muted small">— recorded positions of one vehicle</span></li>
                <li><Link to="/route">Route details (classic)</Link></li>
              </ul>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
