import { useEffect, useRef, useState } from 'react';
import { transitGet } from '../api/client';
import { progress, distance } from '../utils/navigation';

export default function useWalkingNavigation(onStop, location) {
  const [walk, setWalk] = useState(null);
  const [active, setActive] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setTick(value => value + 1), 1000);
    return () => clearInterval(timer);
  }, [active]);
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
    setError(location ? '' : 'Waiting for GPS. Allow location access before starting navigation.'); setActive(true);
  };
  useEffect(() => {
    if (!active || !location) return;
    const version = generation.current;
    const update = async () => {
      if (version !== generation.current) return;
      const loc = location;
      const accurate = loc.accuracy <= 50 && Date.now() - loc.timestamp <= 30000;
      setError(previous => accurate ? (previous.startsWith('Location is approximate') || previous.startsWith('Waiting for GPS') ? '' : previous) : 'Location is approximate or outdated. Nearby stops are provisional; walking guidance needs a recent GPS fix within 50 metres.');
      const current = route.current;
      const p = current?.available ? progress(current.coordinates, loc) : null;
      const offRoute = p && p.offset > Math.max(35, loc.accuracy * 2);
      if (accurate && p && !offRoute && p.remaining < 15) { setError('You have reached the stop.'); stop(); return; }
      if (request.current || (current && !accurate) || (current?.available && !offRoute) || Date.now() - lastRoute.current < 30000) return;
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
  }, [active, location, tick]);
  const p = walk?.available && location ? progress(walk.coordinates, location) : null;
  const offRoute = p && p.offset > Math.max(35, (location?.accuracy || 0) * 2);
  const maneuver = p && !offRoute && location.accuracy <= 50 && Date.now() - location.timestamp <= 30000 ? (walk.maneuvers || []).find(m => m.toIndex > p.segment) : null;
  return { location, walk, active, busy, error, start, stop, remaining: p?.remaining ?? (walk?.stop && location ? distance(location, walk.stop) : null), offRoute, maneuver,
    secondsRemaining: active && !offRoute && location?.accuracy <= 50 && Date.now() - location.timestamp <= 30000 && p && p.total > 0 ? walk.durationSeconds * p.remaining / p.total : null };
}
