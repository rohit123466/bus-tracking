import Icon from '../ui/Icon';
import { Eta, RouteCheckNote, RouteChip } from '../ui/Badges';
import { sortByEta, arrivalsByRoute } from '../../utils/liveMap';
import { ageText, distanceText, plural } from '../../utils/format';

/**
 * Upcoming buses at a stand, soonest first. Each row says how its time was obtained and how old
 * the bus's GPS fix is; a bus without a computable time is listed as "No estimate", never given one.
 */
export default function ArrivalList({ upcoming, now, limit = 8, onSelect, selectedId, emptyText }) {
  const rows = sortByEta(upcoming).slice(0, limit);
  if (!rows.length) {
    return <p className="small muted arr-empty" style={{ margin: '4px 0' }}>{emptyText ?? 'No bus is being tracked towards this stop right now.'}</p>;
  }
  return (
    <ul className="list-reset arr-list">
      {rows.map(u => {
        const Wrapper = onSelect ? 'button' : 'div';
        return (
          <li key={u.vehicleId}>
            <Wrapper
              {...(onSelect ? { type: 'button', className: 'row-btn arr-row', onClick: () => onSelect(u), 'aria-current': selectedId === u.vehicleId || undefined } : { className: 'arr-row' })}
            >
              <RouteChip upcoming={u} />
              <span className="grow">
                <span className="meta">
                  {u.distanceMeters != null && <span>{distanceText(u.distanceMeters)} away</span>}
                  {u.remainingStops != null && <span>{plural(u.remainingStops, 'stop')} before</span>}
                  <span title="Age of the bus's last GPS fix">
                    <Icon name="clock" size={13} /> GPS {ageText(u.lastGpsUpdate, now)}
                  </span>
                  {u.vehicleId && <span>{u.vehicleId}</span>}
                </span>
                {u.reason && <span className="small muted arr-reason">{u.reason}</span>}
                <RouteCheckNote item={u} />
              </span>
              <Eta upcoming={u} showSource now={now} />
            </Wrapper>
          </li>
        );
      })}
    </ul>
  );
}

/** Route numbers serving the stand with no tracked bus right now (collapsed: busy stands have 30+). */
export function IdleRoutes({ stand, onSelectRoute }) {
  const { idle, tracked } = arrivalsByRoute(stand);
  if (!idle.length) return null;
  return (
    <details className="idle-routes">
      <summary>{tracked.length ? 'Also stops here' : 'Routes stopping here'}: {plural(idle.length, 'route')} with no tracked bus now</summary>
      <div className="row wrap" style={{ marginTop: 8, gap: 6 }}>
        {idle.map(r => onSelectRoute
          ? <button key={r} type="button" className="route-chip route-chip-quiet" onClick={() => onSelectRoute(r)} aria-label={`Show route ${r} on the map`}>{r}</button>
          : <span key={r} className="route-chip route-chip-quiet">{r}</span>)}
      </div>
    </details>
  );
}
