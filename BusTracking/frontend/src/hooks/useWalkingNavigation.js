import { useEffect, useRef, useState } from 'react';
import { transitGet } from '../api/client';
import { progress, distance } from '../utils/navigation';

export default function useWalkingNavigation(onStop) {
  const [location, setLocation] = useState(null);
  const [walk, setWalk] = useState(null);
  const [active, setActive] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const watch = useRef(null), request = useRef(null), route = useRef(null), lastRoute = useRef(0), generation = useRef(0);
  const callback = useRef(onStop);
  useEffect(() => { callback.current = onStop; }, [onStop]);
  const stop = () => {
    generation.current++;
    if (watch.current !== null) navigator.geolocation?.clearWatch(watch.current);
    watch.current = null; request.current?.abort(); request.current = null;
    setActive(false); setBusy(false);
  };
  useEffect(() => () => {
    generation.current++;
    if (watch.current !== null) navigator.geolocation?.clearWatch(watch.current);
    request.current?.abort();
  }, []);
  const start = () => {
    stop(); setWalk(null); route.current = null; lastRoute.current = 0;
    if (!navigator.geolocation) { setError('Geolocation is not supported by this browser.'); return; }
    setError(''); setActive(true);
    const version = generation.current;
    const update = async position => {
      if (version !== generation.current) return;
      const loc = { lat: position.coords.latitude, lng: position.coords.longitude, accuracy: position.coords.accuracy, timestamp: position.timestamp };
      setLocation(loc);
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
    watch.current = navigator.geolocation.watchPosition(update, e => {
      if (version !== generation.current) return;
      setError(e.code === 1 ? 'GPS permission denied. Enable location permission to find a nearby stop.' : 'Unable to obtain GPS location. Please retry.'); stop();
    }, { enableHighAccuracy: true, maximumAge: 3000, timeout: 15000 });
  };
  const p = walk?.available && location ? progress(walk.coordinates, location) : null;
  const offRoute = p && p.offset > Math.max(35, (location?.accuracy || 0) * 2);
  const maneuver = p && !offRoute && location.accuracy <= 50 ? walk.maneuvers.find(m => m.toIndex > p.segment) : null;
  return { location, walk, active, busy, error, start, stop, remaining: p?.remaining ?? (walk?.stop && location ? distance(location, walk.stop) : null), offRoute, maneuver,
    secondsRemaining: p && p.total > 0 ? walk.durationSeconds * p.remaining / p.total : null };
}
