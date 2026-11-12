import { agoBetween, fmtClock, labelOf, toneOf } from './consoleFormat';

function StatusTile({ label, status, value, detail, live }) {
  return (
    <div className={`cc-status cc-status--${toneOf(status)}`} aria-live={live ? 'polite' : undefined}>
      <span className="cc-status-label">{label}</span>
      <strong className="cc-status-value">{value ?? labelOf(status)}</strong>
      {detail && <span className="cc-status-detail">{detail}</span>}
    </div>
  );
}

/**
 * The four readings a conductor glances at: seat availability, standing
 * area, standing passengers, camera link - plus when the view last changed.
 */
export default function LiveStatusStrip({ live, stale }) {
  if (!live) return null;
  const sa = live.standingArea;
  const standingStatus = live.cameraInstalled ? sa?.status || 'UNKNOWN' : 'NOT_INSTALLED';
  const camera = live.cameraStatus;
  const ago = agoBetween(sa?.lastFrameAt, live.updatedAt);

  return (
    <section className="cc-strip" aria-label="Live bus status">
      <StatusTile
        label="Seat availability"
        status={live.seatAvailability}
        detail={live.seatAvailabilityReason}
        live
      />
      <StatusTile
        label="Standing area"
        status={standingStatus}
        value={live.cameraInstalled ? undefined : 'NO CAMERA'}
        detail={
          live.cameraInstalled
            ? live.conductorInStandingArea
              ? 'Conductor in area · not counted'
              : sa?.stale
                ? 'Holding last confirmed reading'
                : 'Designated standing area'
            : 'Occupancy from tickets only'
        }
        live
      />
      <StatusTile
        label="Standing passengers"
        status={live.standingPassengers > 0 ? 'OCCUPIED' : live.cameraInstalled && sa?.status !== 'UNKNOWN' ? 'EMPTY' : 'UNKNOWN'}
        value={live.cameraInstalled ? live.standingPassengers : '—'}
        detail={`${live.seatedPassengers} seated · ${live.passengersOnBoard} on board`}
      />
      <StatusTile
        label="Camera / IoT"
        status={camera}
        detail={
          live.cameraInstalled
            ? sa?.lastFrameAt
              ? `Last detection ${fmtClock(sa.lastFrameAt)}${ago ? ` · ${ago}` : ''}`
              : 'Waiting for first frame'
            : 'Not fitted on this bus'
        }
      />
      <p className={'cc-updated' + (stale ? ' cc-updated--stale' : '')}>
        <span className={'cc-pulse' + (stale ? ' cc-pulse--off' : '')} aria-hidden="true" />
        {stale ? 'Connection to server lost · showing ' : 'Last updated '}
        {fmtClock(live.updatedAt)}
      </p>
    </section>
  );
}
