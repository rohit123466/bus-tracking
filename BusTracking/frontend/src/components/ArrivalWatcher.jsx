import { useCallback, useEffect, useRef, useState } from 'react';
import { getStopArrivals } from '../api/client';
import usePolling from '../hooks/usePolling';
import { useStore } from '../hooks/useStores';
import { watches, dueAnnouncements, loadAnnounced, saveAnnounced } from '../store/watches';
import Icon from './ui/Icon';

const POLL_MS = 30000;
const TOAST_MS = 15000;

/**
 * Mounted once for the whole app: polls the stop of every arrival alert (also while the tab is in
 * the background, which browsers throttle but still run) and announces each bus once, as a system
 * notification when permitted and always as an in-app toast.
 */
export default function ArrivalWatcher() {
  const list = useStore(watches);
  const [toasts, setToasts] = useState([]);
  const timers = useRef(new Map());

  const dismiss = useCallback((key) => {
    setToasts(t => t.filter(x => x.key !== key));
    clearTimeout(timers.current.get(key));
    timers.current.delete(key);
  }, []);

  const announce = useCallback((items) => {
    for (const item of items) {
      if ('Notification' in window && Notification.permission === 'granted') {
        try {
          new Notification(item.title, { body: item.body, tag: item.key, icon: '/favicon.svg' });
        } catch { /* some mobile browsers only allow notifications from a service worker */ }
      }
      setToasts(t => [...t.filter(x => x.key !== item.key), item].slice(-3));
      timers.current.set(item.key, setTimeout(() => dismiss(item.key), TOAST_MS));
    }
  }, [dismiss]);

  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  return (
    <>
      {list.map(w => <WatchPoller key={w.id} watch={w} onDue={announce} />)}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(t => (
          <div className="toast" key={t.key}>
            <Icon name="bell" />
            <div className="grow">
              <strong>{t.title}</strong>
              <p>{t.body}</p>
            </div>
            <button type="button" className="icon-btn" aria-label="Dismiss alert" onClick={() => dismiss(t.key)}>
              <Icon name="x" size={18} />
            </button>
          </div>
        ))}
      </div>
    </>
  );
}

function WatchPoller({ watch, onDue }) {
  const stop = { id: watch.stopId, name: watch.stopName, lat: watch.lat, lng: watch.lng };
  const { data } = usePolling(watch.id, signal => getStopArrivals(stop, signal), {
    interval: POLL_MS, pauseWhenHidden: false,
  });

  useEffect(() => {
    if (!data?.stand) return;
    const announced = loadAnnounced();
    const due = dueAnnouncements(watch, data.stand.upcoming, announced);
    if (!due.length) return;
    const now = Date.now();
    due.forEach(d => announced.set(d.key, now));
    saveAnnounced(announced);
    onDue(due);
  }, [data, watch, onDue]);

  return null;
}
