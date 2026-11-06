import { useEffect, useId, useRef } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../ui/Icon';
import { ErrorState, Notice, SkeletonList } from '../ui/States';
import { UpdatedAgo } from './AdminWidgets';
import { sectionPath, useAdminPolling } from './adminContext';
import { getRouteDiagnosis } from '../../api/client';
import { REASON_TONE, routeLabel, splitDiagnoses } from '../../utils/admin';
import { ageText, clockText, plural } from '../../utils/format';

const COUNTS = [
  { key: 'assigned', label: 'Known on route' },
  { key: 'reporting', label: 'Reporting now' },
  { key: 'lagging', label: 'Between updates', hint: 'Last fix 2–5 minutes old: usually just a gap between updates.' },
  { key: 'silentToday', label: 'Went silent today' },
  { key: 'notSeenToday', label: 'Not seen today' },
];

function ReasonTag({ reason, label, count }) {
  const tone = REASON_TONE[reason];
  return (
    <span className={`tag${tone ? ` tag-${tone}` : ''}`}>
      {label}{count != null && <strong>{count}</strong>}
    </span>
  );
}

function LastHeard({ bus }) {
  if (!bus.lastSeen) return <span className="muted">Never</span>;
  return (
    <>
      {ageText(bus.lastSeen)}
      <small className="muted">
        {' '}at {clockText(bus.lastSeen)}
        {bus.lastSpeedKmh != null && <>, {Math.round(bus.lastSpeedKmh)} km/h</>}
      </small>
    </>
  );
}

function SilentTable({ buses, routeCode }) {
  return (
    <div className="table-wrap">
      <table className="admin-table diag-table">
        <caption className="vh">Buses that reported earlier today but not in the last 5 minutes</caption>
        <thead>
          <tr><th scope="col">Bus</th><th scope="col">Last heard</th><th scope="col">Likely reason</th></tr>
        </thead>
        <tbody>
          {buses.map((b) => (
            <tr key={b.vehicleId}>
              <th scope="row">
                {b.vehicleId}
                {b.registrationNumber && <small className="muted diag-sub">{b.registrationNumber}</small>}
                {b.latitude != null && (
                  <Link className="small diag-sub" to={sectionPath('map', { route: routeCode, bus: b.vehicleId })}>
                    Last position on map
                  </Link>
                )}
              </th>
              <td><LastHeard bus={b} /></td>
              <td>
                <ReasonTag reason={b.reason} label={b.reasonLabel} />
                <small className="muted diag-sub">{b.detail}</small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Report({ report }) {
  const { silent, notSeen } = splitDiagnoses(report.buses);
  return (
    <>
      <p className="diag-summary">{report.summary}</p>

      <dl className="diag-counts">
        {COUNTS.map((c) => (
          <div key={c.key} title={c.hint}>
            <dt className="small muted">{c.label}</dt>
            <dd>{report.counts[c.key]}</dd>
          </div>
        ))}
      </dl>

      {report.reasons.length > 0 && (
        <section aria-label="Reasons">
          <ul className="list-reset diag-reasons">
            {report.reasons.map((r) => <li key={r.reason}><ReasonTag {...r} /></li>)}
          </ul>
        </section>
      )}

      {silent.length > 0 && (
        <section className="stack">
          <h3 className="diag-h">Went silent today</h3>
          <SilentTable buses={silent} routeCode={report.routeCode} />
        </section>
      )}

      {notSeen.length > 0 && (
        <details className="diag-details">
          <summary>{plural(notSeen.length, 'bus', 'buses')} not seen today</summary>
          <ul className="list-reset diag-notseen">
            {notSeen.map((b) => (
              <li key={b.vehicleId}>
                <span>
                  {b.vehicleId}
                  <small className="muted"> · {b.lastSeen ? `last heard ${new Date(b.lastSeen * 1000).toLocaleDateString('en-IN')}` : 'never heard'}</small>
                </span>
                <ReasonTag reason={b.reason} label={b.reasonLabel} />
              </li>
            ))}
          </ul>
        </details>
      )}

      <Notice tone="info">
        <strong>Not checked</strong>
        <ul className="diag-notchecked">
          {report.notChecked.map((s) => <li key={s}>{s}</li>)}
        </ul>
      </Notice>
    </>
  );
}

/**
 * Modal diagnosis of one route: why each of its buses is not reporting (RouteDiagnosisService).
 * `route`: { routeCode, routeName }. Refreshes every 30 s while open.
 */
export default function RouteDiagnosisDialog({ route, onClose }) {
  const ref = useRef(null);
  const titleId = useId();
  const res = useAdminPolling(
    `route-diagnosis:${route.routeCode}`,
    (signal) => getRouteDiagnosis(route.routeCode, signal),
    { interval: 30000 });

  useEffect(() => {
    const dialog = ref.current;
    const previous = document.activeElement;
    dialog.showModal();
    return () => {
      dialog.close();
      previous?.focus?.();
    };
  }, []);

  return (
    // Esc fires the dialog's own close event; a click on the backdrop lands on the dialog itself.
    // The close event is queued, so one from the effect cleanup can arrive after a re-open (StrictMode
    // re-runs effects): only a dialog that is still closed means the admin closed it.
    <dialog ref={ref} className="diag" aria-labelledby={titleId}
      onClose={() => { if (!ref.current?.open) onClose(); }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="diag-body stack">
        <div className="diag-head">
          <span className="route-chip route-chip-lg">{routeLabel(route)}</span>
          <h2 id={titleId}>Why route {routeLabel(route)} is not responding</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">
            <Icon name="x" size={20} />
          </button>
        </div>

        {!res.data && !res.error && <SkeletonList rows={4} label="Loading diagnosis" />}
        {!res.data && res.error && <ErrorState error={res.error} onRetry={res.refresh} what="this route's diagnosis" />}
        {res.data && (
          <>
            <UpdatedAgo updatedAt={res.updatedAt} error={res.error} staleAfterMs={60000} />
            <Report report={res.data} />
          </>
        )}
      </div>
    </dialog>
  );
}
