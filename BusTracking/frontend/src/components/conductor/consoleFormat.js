// Labels, tones and time helpers shared by the conductor console panels.

export const STATUS_TONE = {
  AVAILABLE: 'ok',
  EMPTY: 'ok',
  CONNECTED: 'ok',
  OCCUPIED: 'warn',
  NOT_AVAILABLE: 'bad',
  DISCONNECTED: 'bad',
  UNKNOWN: 'neutral',
  WAITING: 'neutral',
  NOT_INSTALLED: 'neutral',
};

export const STATUS_LABEL = {
  AVAILABLE: 'AVAILABLE',
  NOT_AVAILABLE: 'NOT AVAILABLE',
  EMPTY: 'EMPTY',
  OCCUPIED: 'OCCUPIED',
  UNKNOWN: 'NO READING',
  CONNECTED: 'CONNECTED',
  DISCONNECTED: 'DISCONNECTED',
  WAITING: 'WAITING',
  NOT_INSTALLED: 'NOT FITTED',
};

export const toneOf = (status) => STATUS_TONE[status] || 'neutral';
export const labelOf = (status) => STATUS_LABEL[status] || status || '—';

export const fmtClock = (iso) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—';

export const fmtShortClock = (iso) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

/** "4 s ago" between two server timestamps, so client clock skew does not matter. */
export function agoBetween(iso, nowIso) {
  if (!iso || !nowIso) return null;
  const s = Math.max(0, Math.round((new Date(nowIso) - new Date(iso)) / 1000));
  if (s < 60) return `${s} s ago`;
  const m = Math.floor(s / 60);
  return m < 60 ? `${m} min ago` : `${Math.floor(m / 60)} h ago`;
}

export const money = (amount, currency = 'INR') =>
  amount == null ? '—' : `${currency === 'INR' ? '₹' : `${currency} `}${Number(amount).toFixed(2)}`;
