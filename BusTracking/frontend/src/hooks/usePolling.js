import { useCallback, useEffect, useRef, useState } from 'react';
import { classifyError, isAbort } from '../api/errors';

const EMPTY = { data: undefined, error: null, loading: false, updatedAt: null };

/**
 * Fetches `fetcher(signal)` now and then every `interval` ms while `key` stays the same.
 *
 * - A new `key` aborts the request in flight and starts over (data is cleared unless
 *   `keepPrevious`), so a stale response can never overwrite a newer selection.
 * - Failures keep the last good data and retry with exponential backoff (capped at `maxBackoff`).
 * - While the tab is hidden nothing is fetched (unless `pauseWhenHidden` is false); on return, or
 *   when the browser comes back online, it refreshes immediately.
 * - `interval: 0` fetches once per key.
 * - Everything is cancelled on unmount.
 */
export default function usePolling(key, fetcher, {
  interval = 10000, enabled = true, keepPrevious = false, pauseWhenHidden = true, maxBackoff = 60000,
} = {}) {
  const [state, setState] = useState({ ...EMPTY, key: null });
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  useEffect(() => { fetcherRef.current = fetcher; });

  const active = enabled && key != null;

  useEffect(() => {
    if (!active) return undefined;
    let disposed = false, timer = null, ctrl = null, inFlight = false, failures = 0, lastRun = 0;
    const hidden = () => pauseWhenHidden && typeof document !== 'undefined' && document.hidden;

    setState(s => (s.key === key || keepPrevious)
      ? { ...s, key, loading: true }
      : { ...EMPTY, key, loading: true });

    const schedule = (ms) => {
      clearTimeout(timer);
      if (!disposed && interval > 0) timer = setTimeout(run, ms);
    };

    async function run() {
      if (disposed || inFlight) return;
      if (hidden()) return; // resumed by the visibilitychange listener
      inFlight = true;
      lastRun = Date.now();
      ctrl = new AbortController();
      try {
        const data = await fetcherRef.current(ctrl.signal);
        if (disposed) return;
        failures = 0;
        setState({ data, error: null, loading: false, updatedAt: Date.now(), key });
      } catch (err) {
        if (disposed || isAbort(err)) return;
        failures++;
        const error = classifyError(err);
        setState(s => ({ ...s, key, error, loading: false }));
        if (!error.retryable) return; // a 404/400 will not fix itself; wait for refresh()
      } finally {
        inFlight = false;
      }
      schedule(failures ? Math.min(maxBackoff, Math.max(interval, 5000) * 2 ** (failures - 1)) : interval);
    }

    const resume = () => {
      if (hidden() || inFlight) return;
      if (Date.now() - lastRun >= Math.min(interval || Infinity, 5000) || failures) { clearTimeout(timer); run(); }
    };

    document.addEventListener('visibilitychange', resume);
    window.addEventListener('online', resume);
    run();
    return () => {
      disposed = true;
      clearTimeout(timer);
      ctrl?.abort();
      document.removeEventListener('visibilitychange', resume);
      window.removeEventListener('online', resume);
    };
  }, [active, key, interval, keepPrevious, pauseWhenHidden, maxBackoff, tick]);

  const refresh = useCallback(() => setTick(t => t + 1), []);
  // Between a key change and the effect running, report "loading" rather than an empty result.
  const current = !active ? EMPTY
    : state.key === key || keepPrevious ? state
    : { ...EMPTY, loading: true };
  return { ...current, refresh };
}
