import { useId, useState } from 'react';
import Icon from '../ui/Icon';
import { useStore } from '../../hooks/useStores';
import useNotificationPermission, { NOTIFICATION_TEXT } from '../../hooks/useNotifications';
import { watches, addWatch, removeWatch, watchId, THRESHOLD_OPTIONS, MAX_WATCHES } from '../../store/watches';

/** "Alert me when a bus is N min away" for one stop, optionally one route. */
export default function NotifyControl({ stop, routes = [] }) {
  const id = useId();
  const list = useStore(watches);
  const { permission, request } = useNotificationPermission();
  const [open, setOpen] = useState(false);
  const [route, setRoute] = useState('');
  const [threshold, setThreshold] = useState(5);
  const existing = list.filter(w => w.stopId === stop.id);

  const save = async () => {
    if (permission === 'default') await request();
    addWatch({ stop, routeNumber: route || null, thresholdMinutes: threshold });
    setOpen(false);
  };

  return (
    <div className="stack" style={{ gap: 8 }}>
      {existing.map(w => (
        <div key={w.id} className="row notify-row">
          <Icon name="bell" size={18} />
          <span className="grow small">
            Alert when {w.routeNumber ? `route ${w.routeNumber}` : 'any tracked bus'} is ≤ {w.thresholdMinutes} min away
          </span>
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => removeWatch(w.id)}>Turn off</button>
        </div>
      ))}
      {!open ? (
        <button type="button" className="btn btn-sm" onClick={() => setOpen(true)} aria-expanded={false} aria-controls={id}>
          <Icon name="bell" size={16} /> {existing.length ? 'Add another alert' : 'Alert me when a bus is near'}
        </button>
      ) : (
        <fieldset className="notify-form" id={id}>
          <legend className="field-label">Arrival alert for {stop.name}</legend>
          <div className="row wrap" style={{ gap: 12 }}>
            <label className="field">
              <span className="small">Route</span>
              <select className="select-input" value={route} onChange={e => setRoute(e.target.value)}>
                <option value="">Any tracked bus</option>
                {routes.map(r => <option key={r} value={r}>Route {r}</option>)}
              </select>
            </label>
            <label className="field">
              <span className="small">When it is</span>
              <select className="select-input" value={threshold} onChange={e => setThreshold(Number(e.target.value))}>
                {THRESHOLD_OPTIONS.map(m => <option key={m} value={m}>{m} min away or less</option>)}
              </select>
            </label>
          </div>
          <p className="small muted" style={{ margin: 0 }}>
            Works only while this site is open in a tab (no background push). Uses estimated arrival times,
            so it can be early or late. {NOTIFICATION_TEXT[permission]}
            {list.length >= MAX_WATCHES && !list.some(w => w.id === watchId(stop.id, route)) && ` You can keep ${MAX_WATCHES} alerts; the oldest will be replaced.`}
          </p>
          <div className="row">
            <button type="button" className="btn btn-primary btn-sm" onClick={save}>Save alert</button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>Cancel</button>
          </div>
        </fieldset>
      )}
    </div>
  );
}
