import { agoBetween, fmtClock, labelOf, toneOf } from './consoleFormat';

const EVENT_TONE = {
  STANDING_OCCUPIED: 'warn',
  STANDING_CLEAR: 'ok',
  COUNT_CHANGED: 'warn',
  CONDUCTOR_IN_AREA: 'info',
  CONDUCTOR_LEFT_AREA: 'info',
  TICKET_ISSUED: 'info',
  SIGNAL_LOST: 'bad',
  SIGNAL_RESTORED: 'ok',
};

/**
 * C. Standing area monitoring and E. live camera/IoT status. Every value is
 * the backend tracker's state; the panel only displays it.
 */
export default function StandingAreaPanel({ live, busy, onSelectPreset }) {
  if (!live) return null;

  if (!live.cameraInstalled) {
    return (
      <section className="t-card">
        <h3 className="t-card-title">Standing area monitoring</h3>
        <p className="camera-status">
          <span className="status-dot status-dot--off" /> No standing-area camera fitted on {live.busNumber}
        </p>
        <p className="muted small">Seat availability for this bus is derived from tickets only.</p>
      </section>
    );
  }

  const sa = live.standingArea;
  const ago = agoBetween(sa.lastFrameAt, live.updatedAt);
  const changedAgo = agoBetween(sa.statusChangedAt, live.updatedAt);

  return (
    <section className="t-card">
      <div className="t-card-row">
        <h3 className="t-card-title">Standing area monitoring</h3>
        <span className={`cc-pill cc-pill--${toneOf(live.cameraStatus)}`}>
          <span className={'status-dot' + (live.cameraStatus === 'CONNECTED' ? ' status-dot--on' : ' status-dot--off')} />
          {labelOf(live.cameraStatus)}
        </span>
      </div>

      <div className={`cc-zone cc-zone--${toneOf(sa.status)}`}>
        <div>
          <span className="cc-zone-label">Standing area</span>
          <strong className="cc-zone-value">{labelOf(sa.status)}</strong>
          {changedAgo && sa.status !== 'UNKNOWN' && <span className="muted small">since {changedAgo}</span>}
        </div>
        <div className="cc-zone-count">
          <strong>{sa.passengerCount}</strong>
          <span>standing passenger{sa.passengerCount === 1 ? '' : 's'}</span>
        </div>
      </div>

      {sa.stale && (
        <p className="warn-note" role="status">
          Camera signal lost. The last confirmed reading is held until frames arrive again; nothing is guessed.
        </p>
      )}
      {live.conductorInStandingArea && (
        <p className="cc-info-note" role="status">
          Conductor detected in the standing area — identified as staff and excluded from the passenger count.
        </p>
      )}

      <dl className="cc-dl cc-dl--compact">
        <div className="cc-dl-row">
          <dt>Latest detection</dt>
          <dd>{sa.lastFrameAt ? `${fmtClock(sa.lastFrameAt)}${ago ? ` (${ago})` : ''}` : 'Waiting for first frame'}</dd>
        </div>
        <div className="cc-dl-row">
          <dt>Being confirmed</dt>
          <dd>{sa.candidates ? `${sa.candidates} new detection${sa.candidates === 1 ? '' : 's'} not yet counted` : 'None'}</dd>
        </div>
        <div className="cc-dl-row">
          <dt>Frames</dt>
          <dd>
            {sa.framesApplied} processed · {sa.framesIgnored} repeated or late, ignored
            {sa.lastSequence != null && <span className="muted small"> · #{sa.lastSequence}</span>}
          </dd>
        </div>
        <div className="cc-dl-row">
          <dt>Source</dt>
          <dd>
            {live.presetFeed ? 'Preset camera feed' : sa.source || 'Camera device'}
            {live.presets?.length > 0 && (
              <select
                className="cc-preset"
                aria-label="Camera feed scenario"
                value={live.activePresetId || ''}
                disabled={busy}
                onChange={(e) => onSelectPreset(e.target.value || null)}
              >
                <option value="">Paused (device input only)</option>
                {live.presets.map((p) => (
                  <option key={p.id} value={p.id} title={p.description}>
                    {p.label}
                  </option>
                ))}
              </select>
            )}
          </dd>
        </div>
      </dl>
      {live.presets?.length > 0 && live.activePresetId && (
        <p className="muted small">{live.presets.find((p) => p.id === live.activePresetId)?.description}</p>
      )}

      <h4 className="subhead">Activity</h4>
      {sa.events.length ? (
        <ol className="cc-events">
          {sa.events.slice(0, 12).map((e, i) => (
            <li key={`${e.at}-${e.type}-${i}`} className={`cc-event cc-event--${EVENT_TONE[e.type] || 'info'}`}>
              <time>{fmtClock(e.at)}</time>
              <span>{e.message}</span>
            </li>
          ))}
        </ol>
      ) : (
        <p className="muted small">No activity yet.</p>
      )}
    </section>
  );
}
