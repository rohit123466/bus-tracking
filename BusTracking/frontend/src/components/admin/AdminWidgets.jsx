import { useEffect, useId, useRef } from 'react';
import Icon from '../ui/Icon';
import { useNow } from '../../hooks/useStores';
import { ageText } from '../../utils/format';
import { countdownText, SESSION_WARN_MS, STATUS_META } from '../../utils/admin';

/** "Updated 4 s ago" with a dot: pulsing while fresh, amber when old or failing, grey when paused. */
export function UpdatedAgo({ updatedAt, error, paused, staleAfterMs = 30000 }) {
  const now = useNow(1000) * 1000;
  if (!updatedAt) return <span className="updated muted small" role="status">{error ? 'Not loaded yet' : 'Loading…'}</span>;
  const old = now - updatedAt > staleAfterMs;
  const tone = paused ? 'paused' : error || old ? 'warn' : 'live';
  const text = paused ? 'Paused' : error ? 'Refresh failing' : 'Live';
  return (
    <span className={`updated updated-${tone}`} title={new Date(updatedAt).toLocaleString('en-IN')}>
      <span className={`dot${tone === 'live' ? ' dot-pulse' : ''}`} aria-hidden="true" />
      <span>{text} · updated {ageText(updatedAt / 1000, now / 1000)}</span>
    </span>
  );
}

/**
 * Time left on the admin JWT. Calls onWarn once inside the last 5 minutes and onExpire at zero
 * (there is no refresh endpoint, so the only way to continue is to log in again).
 */
export function SessionTimer({ expiresAt, onWarn, onExpire }) {
  const now = useNow(1000) * 1000;
  const warned = useRef(false);
  const left = expiresAt ? expiresAt - now : null;
  useEffect(() => {
    if (left == null) return;
    if (left <= 0) onExpire();
    else if (left <= SESSION_WARN_MS && !warned.current) {
      warned.current = true;
      onWarn(left);
    }
  }, [left, onWarn, onExpire]);
  if (left == null) return null;
  const warn = left <= SESSION_WARN_MS;
  return (
    <span className={`tag${warn ? ' tag-warn' : ''}`} title={`Session ends at ${new Date(expiresAt).toLocaleString('en-IN')}`}>
      <Icon name="clock" size={16} />
      <span><span className="vh">Session ends in </span><span aria-hidden="true" className="tag-word">Session </span>{countdownText(left)}</span>
    </span>
  );
}

const SOCKET_TEXT = {
  connected: { text: 'Live push', tone: 'live', hint: 'Bus positions arrive over the WebSocket as the feed reports them.' },
  connecting: { text: 'Connecting…', tone: 'warn', hint: 'Opening the WebSocket for live positions.' },
  reconnecting: { text: 'Socket down', tone: 'danger', hint: 'WebSocket lost; reconnecting. The live map polls every 10 s meanwhile.' },
};

export function SocketPill({ status, compact = false }) {
  const s = SOCKET_TEXT[status] ?? SOCKET_TEXT.connecting;
  return (
    <span className={`tag tag-${s.tone}`} title={s.hint}>
      <span className={`dot${status === 'connected' ? ' dot-pulse' : ''}`} aria-hidden="true" />
      {compact ? <span className="vh">{s.text}</span> : s.text}
    </span>
  );
}

/** Info button whose explanation shows on hover and on keyboard focus (and is its description). */
export function Tip({ text, label = 'What does this mean?' }) {
  const id = useId();
  return (
    <span className="tip">
      <button type="button" className="tip-btn" aria-label={label} aria-describedby={id}>
        <Icon name="info" size={14} />
      </button>
      <span role="tooltip" id={id} className="tip-text">{text}</span>
    </span>
  );
}

/** Column header that sorts on click; `sort` is { key, dir }. */
export function SortHeader({ label, field, sort, onSort, className }) {
  const active = sort.key === field;
  const next = active && sort.dir === 'asc' ? 'desc' : active ? 'asc' : field === 'label' || field === 'vehicleId' || field === 'route' ? 'asc' : 'desc';
  return (
    <th scope="col" className={className} aria-sort={active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className="th-btn" onClick={() => onSort({ key: field, dir: next })}>
        {label}
        <Icon name={active && sort.dir === 'asc' ? 'chevronUp' : 'chevronDown'} size={14} className={active ? '' : 'th-idle'} />
      </button>
    </th>
  );
}

/** Live status as a labelled badge (never colour alone), with the definition as a tooltip. */
export function StatusBadge({ status }) {
  const meta = STATUS_META[status] ?? STATUS_META.NO_DATA;
  return (
    <span className={`badge status-badge status-${meta.tone}`} title={meta.hint}>
      <span className="dot" aria-hidden="true" />{meta.label}
    </span>
  );
}
