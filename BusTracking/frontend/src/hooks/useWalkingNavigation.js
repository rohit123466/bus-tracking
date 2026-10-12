import { useEffect, useRef, useState } from 'react';
import { transitGet } from '../api/client';
import { progress, distance } from '../utils/navigation';

export default function useWalkingNavigation(onStop, location) {
  const [walk, setWalk] = useState(null);
  const [active, setActive] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const request = useRef(null), route = useRef(null), lastRoute = useRef(0), generation = useRef(0);
  const callback = useRef(onStop);
  useEffect(() => { callback.current = onStop; }, [onStop]);
  const stop = () => {
    generation.current++;
    request.current?.abort(); request.current = null;
    setActive(false); setBusy(false);
  };
  useEffect(() => () => {
    generation.current++;
    request.current?.abort();
  }, []);
  const start = () => {
    stop(); setWalk(null); route.current = null; lastRoute.current = 0;
    if (!location) { setError('Waiting for GPS. Allow location access before starting navigation.'); return; }
    setError(''); setActive(true);
  };
  useEffect(() => {
    if (!active || !location) return;
    const version = generation.current;
    const update = async () => {
      if (version !== generation.current) return;
      const loc = location;
      if (loc.accuracy > 50) { setError('GPS accuracy is low. Waiting for a fix within 50 metres before routing.'); return; }
      setError('');
      const current = route.current;
      const p = current?.available ? progress(current.coordinates, loc) : null;
      const offRoute = p && p.offset > Math.max(35, loc.accuracy * 2);
      if (p && !offRoute && p.remaining < 15) { setError('You have reached the stop.'); stop(); return; }
      if (request.current || (current?.available && !offRoute) || Date.now() - lastRoute.current < 30000) return;
      const controller = new AbortController(); request.current = controller; lastRoute.current = Date.now(); setBusy(true);
      try {
        const next = current?.stop ? await transitGet('/navigation/walking', { startLat: loc.lat, startLon: loc.lng, stopId: current.stop.id }, controller.signal)
          : await transitGet('/stops/nearest', { latitude: loc.lat, longitude: loc.lng }, controller.signal);
        if (version !== generation.current) return;
        route.current = next; setWalk(next);
        if (next.stop) callback.current(next.stop);
      } catch (e) { if (!controller.signal.aborted) setError(e.response?.data?.message || 'Walking route unavailable. Please retry.'); }
      finally { if (request.current === controller) { request.current = null; setBusy(false); } }
    };
    update();
  }, [active, location]);
  const p = walk?.available && location ? progress(walk.coordinates, location) : null;
  const offRoute = p && p.offset > Math.max(35, (location?.accuracy || 0) * 2);
  const maneuver = p && !offRoute && location.accuracy <= 50 ? walk.maneuvers.find(m => m.toIndex > p.segment) : null;
  return { location, walk, active, busy, error, start, stop, remaining: p?.remaining ?? (walk?.stop && location ? distance(location, walk.stop) : null), offRoute, maneuver,
    secondsRemaining: p && p.total > 0 ? walk.durationSeconds * p.remaining / p.total : null };
}
