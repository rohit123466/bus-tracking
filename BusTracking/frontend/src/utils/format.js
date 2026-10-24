// Display formatting shared by every page. Pure functions (unit-tested with `node --test`).

/** "just now", "40 s ago", "3 min ago", "2 h ago"; "unknown" without a timestamp. */
export function ageText(epochSeconds, now = Date.now() / 1000) {
  if (!epochSeconds || !Number.isFinite(epochSeconds)) return 'unknown';
  const s = Math.max(0, Math.round(now - epochSeconds));
  if (s < 10) return 'just now';
  if (s < 60) return `${s} s ago`;
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  return `${Math.floor(s / 3600)} h ago`;
}

/** Local wall-clock time, e.g. "14:05". Accepts epoch seconds or milliseconds. */
export function clockText(epoch) {
  if (!epoch || !Number.isFinite(epoch)) return '';
  const ms = epoch < 1e12 ? epoch * 1000 : epoch;
  return new Date(ms).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

/** "under 1 min", "45 min", "1 h 5 min". */
export function durationText(minutes) {
  if (minutes == null || !Number.isFinite(minutes)) return '';
  if (minutes < 0.5) return 'under 1 min';
  const m = Math.max(1, Math.round(minutes));
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60), rest = m % 60;
  return rest ? `${h} h ${rest} min` : `${h} h`;
}

/** "850 m" / "12.4 km"; null when unknown. */
export function distanceText(m) {
  if (m == null || !Number.isFinite(m)) return null;
  return m < 1000 ? `${Math.round(m)} m` : `${(m / 1000).toFixed(1)} km`;
}

export const plural = (n, word, many = `${word}s`) => `${n} ${n === 1 ? word : many}`;
