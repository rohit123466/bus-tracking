import { Link } from 'react-router-dom';
import { EmptyState, ErrorState, Notice, SkeletonList } from '../../ui/States';
import { UpdatedAgo } from '../AdminWidgets';
import usePolling from '../../../hooks/usePolling';
import { getTicketingBuses } from '../../../api/client';
import { loadTone } from '../../../utils/admin';
import { plural } from '../../../utils/format';

const LOAD_TEXT = { low: 'Light load', mid: 'Filling up', high: 'Nearly full' };
const WHEELCHAIR_TEXT = { AVAILABLE: 'free', OCCUPIED: 'occupied', NOT_FITTED: 'not fitted' };

function CameraBadge({ bus }) {
  if (bus.cameraStatus === 'NOT_INSTALLED') return <span className="badge">No camera</span>;
  return (
    <span className="row wrap" style={{ gap: 4 }}>
      {bus.cameraMismatches > 0
        ? <span className="badge badge-danger" title="Seats where the camera and the ticket records disagree">{plural(bus.cameraMismatches, 'camera mismatch', 'camera mismatches')}</span>
        : <span className="badge badge-live">Camera agrees</span>}
      {bus.cameraSimulated && <span className="badge badge-info" title="Camera readings are generated for the demo, not from real hardware">SIMULATED</span>}
    </span>
  );
}

/** Seat-ticketing demo buses (GET /ticketing/buses) as load cards; each opens in the conductor console. */
export default function OccupancySection() {
  const res = usePolling('ticketing-buses', getTicketingBuses, { interval: 15000, keepPrevious: true });
  const buses = res.data ?? [];
  const mismatches = buses.reduce((sum, b) => sum + (b.cameraMismatches ?? 0), 0);
  const avg = buses.length ? Math.round(buses.reduce((sum, b) => sum + b.occupancyPercent, 0) / buses.length) : 0;

  return (
    <div className="admin-pad stack">
      <Notice tone="info">
        <p><strong>DEMO data.</strong> These are the seat-ticketing demonstration buses, not live DTC vehicles. Camera counts are SIMULATED.</p>
      </Notice>
      <div className="admin-toolbar">
        {res.data && (
          <span className="small muted">
            {plural(buses.length, 'bus', 'buses')} · average load {avg}% · {plural(mismatches, 'camera mismatch', 'camera mismatches')}
          </span>
        )}
        <div className="admin-toolbar-end"><UpdatedAgo updatedAt={res.updatedAt} error={res.error} staleAfterMs={45000} /></div>
      </div>

      {!res.data && res.loading && <SkeletonList rows={4} label="Loading occupancy" />}
      {!res.data && res.error && <div className="panel"><ErrorState error={res.error} onRetry={res.refresh} what="occupancy" /></div>}
      {res.data && buses.length === 0 && (
        <div className="panel"><EmptyState icon="users" title="No ticketing buses">The ticketing demo has no buses set up.</EmptyState></div>
      )}

      <ul className="list-reset occ-grid">
        {buses.map((b) => {
          const tone = loadTone(b.occupancyPercent);
          return (
            <li key={b.busNumber} className={`panel occ-card occ-${tone}`}>
              <div className="row">
                <h2 className="grow mono">{b.busNumber}</h2>
                <span className="route-chip route-chip-quiet">{b.routeCode}</span>
              </div>
              <p className="small muted">{b.busTypeLabel}{b.currentStop?.name ? ` · at ${b.currentStop.name}` : ''}</p>
              <div className="occ-meter">
                <span className="bar occ-bar" role="img" aria-label={`Occupancy ${b.occupancyPercent}%, ${LOAD_TEXT[tone]}`}>
                  <span style={{ width: `${Math.min(100, b.occupancyPercent)}%` }} />
                </span>
                <strong>{b.occupancyPercent}%</strong>
              </div>
              <p className="small">
                {LOAD_TEXT[tone]} · {b.onBoard} of {b.capacity} on board · {b.availableSeats} seats free
                {b.wheelchairSpaces > 0 && ` · ♿ ${WHEELCHAIR_TEXT[b.wheelchairStatus] ?? 'unknown'}`}
              </p>
              <CameraBadge bus={b} />
              <Link className="btn btn-sm occ-link" to={`/conductor?bus=${encodeURIComponent(b.busNumber)}`}>
                Open in conductor console<span className="vh"> for {b.busNumber}</span>
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
