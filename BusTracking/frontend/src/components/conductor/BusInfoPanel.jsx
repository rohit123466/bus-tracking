import { fmtClock, fmtShortClock, labelOf, toneOf } from './consoleFormat';

const WHEELCHAIR = { AVAILABLE: 'Free', OCCUPIED: 'Occupied', NOT_FITTED: 'Not fitted' };

function Row({ term, children }) {
  return (
    <div className="cc-dl-row">
      <dt>{term}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/** A. Bus information and B. seat availability for the hop the bus is about to travel. */
export default function BusInfoPanel({ live }) {
  if (!live) return null;
  const load = live.occupancyPercent;
  const loadTone = load >= 90 ? 'bad' : load >= 70 ? 'warn' : 'ok';

  return (
    <div className="cc-two">
      <section className="t-card">
        <h3 className="t-card-title">Bus &amp; trip</h3>
        <dl className="cc-dl">
          <Row term="Bus number"><strong>{live.busNumber}</strong> <span className="muted small">{live.busTypeLabel}</span></Row>
          <Row term="Route">{live.routeCode} · {live.routeName}</Row>
          <Row term="Trip">
            {live.origin} → {live.destination}
            {live.tripStartedAt && <span className="muted small"> · started {fmtShortClock(live.tripStartedAt)}</span>}
          </Row>
          <Row term="Position">
            At <strong>{live.currentStop}</strong>
            {live.nextStop ? <span className="muted"> · next {live.nextStop}</span> : <span className="muted"> · final stop</span>}
          </Row>
          <Row term="Conductor">
            <strong>{live.conductorId}</strong>
            <span className="muted small">
              {' '}· {live.conductorTicketsIssued} ticket{live.conductorTicketsIssued === 1 ? '' : 's'} issued
              {live.conductorLastTicketAt ? `, last ${fmtClock(live.conductorLastTicketAt)}` : ''}
            </span>
          </Row>
          <Row term="Occupancy">
            <span className={`cc-pill cc-pill--${loadTone}`}>{load}% seated load</span>{' '}
            <span className="muted small">{live.passengersOnBoard} on board ({live.standingPassengers} standing)</span>
          </Row>
        </dl>
      </section>

      <section className="t-card">
        <div className="t-card-row">
          <h3 className="t-card-title">Seat availability</h3>
          <span className={`cc-pill cc-pill--${toneOf(live.seatAvailability)}`}>{labelOf(live.seatAvailability)}</span>
        </div>
        <div className="cc-metrics">
          <div className="cc-metric"><span>Total seats</span><strong>{live.totalSeats}</strong></div>
          <div className="cc-metric"><span>Occupied</span><strong>{live.occupiedSeats}</strong></div>
          <div className="cc-metric cc-metric--ok"><span>Available</span><strong>{live.availableSeats}</strong></div>
          <div className={'cc-metric' + (live.standingPassengers > 0 ? ' cc-metric--warn' : '')}>
            <span>Standing</span><strong>{live.cameraInstalled ? live.standingPassengers : '—'}</strong>
          </div>
        </div>
        <div className="cc-loadbar" role="img" aria-label={`${live.occupiedSeats} of ${live.totalSeats} seats occupied`}>
          <span style={{ width: `${Math.min(100, (live.occupiedSeats / Math.max(1, live.totalSeats)) * 100)}%` }} />
        </div>
        <p className="muted small cc-basis">
          {live.seatAvailabilityReason}
          {live.wheelchairSpaces > 0 && ` · ♿ space ${WHEELCHAIR[live.wheelchairStatus]?.toLowerCase()}`}
        </p>
      </section>
    </div>
  );
}
