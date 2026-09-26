import './Ticketing.css';

const WHEELCHAIR_LABEL = { AVAILABLE: 'Free', OCCUPIED: 'Occupied', NOT_FITTED: 'Not fitted' };

/** Real-time occupancy of one bus; every number comes from the backend summary. */
export default function OccupancyDashboard({ bus }) {
  if (!bus) return null;
  const kpis = [
    { label: 'Total seats', value: bus.totalSeats, tone: '' },
    { label: 'Available', value: bus.availableSeats, tone: 'green' },
    { label: 'Occupied', value: bus.occupiedSeats, tone: 'red' },
    { label: 'Reserved (free priority)', value: `${bus.reservedSeats}/${bus.prioritySeats}`, tone: 'yellow' },
    {
      label: bus.wheelchairSpaces ? `♿ Wheelchair space (${bus.wheelchairSpaces})` : '♿ Wheelchair space',
      value: WHEELCHAIR_LABEL[bus.wheelchairStatus],
      tone: 'blue',
    },
    { label: 'Tickets sold', value: bus.ticketsSold, tone: '' },
    {
      label: 'Camera',
      value: bus.cameraStatus === 'NOT_INSTALLED' ? 'None' : bus.cameraStatus,
      sub: bus.cameraStatus === 'NOT_INSTALLED' ? null : `${bus.cameraMismatches} mismatch${bus.cameraMismatches === 1 ? '' : 'es'}${bus.cameraSimulated ? ' · demo' : ''}`,
      tone: bus.cameraMismatches > 0 ? 'red' : '',
    },
  ];

  return (
    <section className="t-card">
      <div className="t-card-row">
        <h3 className="t-card-title">
          📊 {bus.busNumber} <span className="muted">· {bus.busTypeLabel} · {bus.routeCode}</span>
        </h3>
        <span className="muted small">
          At {bus.currentStop?.name}
          {bus.nextStop ? ` → next ${bus.nextStop.name}` : ' (final stop)'}
        </span>
      </div>

      <div className="occupancy-headline">
        <div className="occupancy-bar occupancy-bar--large" aria-label={`Occupancy ${bus.occupancyPercent}%`}>
          <span style={{ width: `${Math.min(100, bus.occupancyPercent)}%` }} />
        </div>
        <strong>{bus.occupancyPercent}%</strong>
        <span className="muted small">
          {bus.onBoard} of {bus.capacity} places occupied now
        </span>
      </div>

      <div className="t-kpi-grid">
        {kpis.map((k) => (
          <div key={k.label} className={`t-kpi t-kpi--${k.tone || 'plain'}`}>
            <div className="t-kpi-value">{k.value}</div>
            <div className="t-kpi-label">{k.label}</div>
            {k.sub && <div className="t-kpi-sub">{k.sub}</div>}
          </div>
        ))}
      </div>

      <h4 className="subhead">Free places per route segment</h4>
      <ul className="hop-list">
        {bus.segments.map((h) => (
          <li key={h.fromSequence} className={h.soldOut ? 'hop hop--soldout' : 'hop'}>
            <span className="hop-name">
              {h.fromStop.split(' · ')[0]} → {h.toStop.split(' · ')[0]}
            </span>
            <span className="hop-bar">
              <span style={{ width: `${(h.availableSeats / Math.max(1, bus.totalSeats)) * 100}%` }} />
            </span>
            <span className="hop-count">
              {h.soldOut ? 'Sold out' : `${h.availableSeats} seats`}
              {bus.wheelchairSpaces > 0 && ` · ♿ ${h.availableWheelchair ? 'free' : 'taken'}`}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
