/**
 * useWalkingNavigation
 *
 * Walking directions from the user's GPS location to a GTFS bus stop.
 *
 * Behaviour:
 *  - If `targetStop` is provided: routes directly to that stop via
 *    GET /api/transit/navigation/walking (ORS pedestrian route when configured,
 *    straight-line fallback otherwise).
 *  - If no `targetStop`: calls GET /api/transit/stops/nearest to find the
 *    nearest stop and calls onStop() so the parent can set it as the selected stop.
 *  - Re-routes to the new target whenever `targetStop` changes while active.
 *  - Progress is tracked on every GPS tick; recalculates when off-route.
 *  - 30-second cooldown between network requests; bypassed on target change.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { transitGet } from '../api/client';
import { distance, progress } from '../utils/navigation';

export default function useWalkingNavigation(onStop, location, targetStop) {
  const [walk, setWalk]     = useState(null);
  const [active, setActive] = useState(false);
  const [error, setError]   = useState('');
  const [busy, setBusy]     = useState(false);

  // Refs that must not re-trigger effects when they change
  const requestRef   = useRef(null);  // in-flight AbortController
  const routeRef     = useRef(null);  // last successful Walk result
  const lastFetchAt  = useRef(0);     // ms timestamp of last fetch
  const generationRef = useRef(0);    // bumped on reset; cancels stale callbacks
  const onStopRef    = useRef(onStop);
  const activeRef    = useRef(false); // shadow of `active` readable inside async fn

  useEffect(() => { onStopRef.current = onStop; }, [onStop]);
  useEffect(() => { activeRef.current = active; }, [active]);

  // ── Cancel everything and reset ──────────────────────────────────────────
  const stopNav = useCallback(() => {
    generationRef.current++;
    requestRef.current?.abort();
    requestRef.current = null;
    activeRef.current = false;
    setActive(false);
    setBusy(false);
    setError('');
  }, []);

  // ── Start navigation ─────────────────────────────────────────────────────
  const start = useCallback(() => {
    if (!location) {
      setError('Allow location access first.');
      return;
    }
    generationRef.current++;
    requestRef.current?.abort();
    requestRef.current = null;
    routeRef.current = null;
    lastFetchAt.current = 0;
    setWalk(null);
    setError('');
    activeRef.current = true;
    setActive(true);
  }, [location]);

  // ── Unmount cleanup ──────────────────────────────────────────────────────
  useEffect(() => () => {
    generationRef.current++;
    requestRef.current?.abort();
  }, []);

  // ── Force re-fetch when target stop changes mid-navigation ───────────────
  useEffect(() => {
    if (activeRef.current) lastFetchAt.current = 0;
  }, [targetStop]);

  // ── Core navigation effect — runs on GPS update while active ────────────
  useEffect(() => {
    if (!active || !location) return;

    // Ignore if GPS accuracy is unusable (>150 m)
    if (location.accuracy > 150) {
      setError(`GPS accuracy is low (±${Math.round(location.accuracy)} m). Move to an open area.`);
      return;
    }
    setError('');

    const gen = generationRef.current;

    const fetchRoute = async () => {
      // Guard: already have an in-flight request
      if (requestRef.current) return;

      // Guard: route was fetched recently and user is still on track
      const current = routeRef.current;
      if (current && Date.now() - lastFetchAt.current < 30000) {
        if (current.available && current.coordinates?.length > 0) {
          const p = progress(current.coordinates, location);
          const offRoute = p.offset > Math.max(45, location.accuracy * 2);
          if (!offRoute) return;   // on-track, no need to re-fetch yet
        } else {
          return; // fallback result — don't hammer the server
        }
      }

      const ctrl = new AbortController();
      requestRef.current = ctrl;
      setBusy(true);

      try {
        let result;

        if (targetStop) {
          // Route to the user-selected stop
          result = await transitGet(
            '/navigation/walking',
            { startLat: location.lat, startLon: location.lng, stopId: targetStop.id },
            ctrl.signal
          );
        } else if (current?.stop) {
          // Re-route to the same stop (recalculation)
          result = await transitGet(
            '/navigation/walking',
            { startLat: location.lat, startLon: location.lng, stopId: current.stop.id },
            ctrl.signal
          );
        } else {
          // No stop selected — find the nearest one
          result = await transitGet(
            '/stops/nearest',
            { latitude: location.lat, longitude: location.lng },
            ctrl.signal
          );
          // Inform parent of found stop so it can populate the boarding stop field
          if (result?.stop && gen === generationRef.current) {
            onStopRef.current?.(result.stop);
          }
        }

        if (gen !== generationRef.current || ctrl.signal.aborted) return;

        routeRef.current = result;
        lastFetchAt.current = Date.now();
        setWalk(result);

        // Check if we've already arrived
        if (result?.available && result.coordinates?.length > 0) {
          const p = progress(result.coordinates, location);
          if (p.remaining < 15) {
            setError(`You have arrived at ${result.stop?.name ?? 'the stop'}.`);
            stopNav();
          }
        }
      } catch (err) {
        if (ctrl.signal.aborted || gen !== generationRef.current) return;
        const msg = err?.response?.data?.message;
        setError(msg || 'Could not fetch walking route. Check your connection.');
      } finally {
        if (requestRef.current === ctrl) {
          requestRef.current = null;
          setBusy(false);
        }
      }
    };

    fetchRoute();
  }, [active, location, targetStop, stopNav]);

  // ── Computed progress (re-derived every render from latest location) ─────
  const p =
    walk?.available && location && walk.coordinates?.length > 0
      ? progress(walk.coordinates, location)
      : null;

  const offRoute = p
    ? p.offset > Math.max(45, (location?.accuracy ?? 0) * 2)
    : false;

  const maneuver =
    p && !offRoute
      ? (walk.maneuvers?.find(m => m.toIndex > p.segment) ?? null)
      : null;

  const remaining =
    p?.remaining ??
    (walk?.stop && location ? distance(location, { lat: walk.stop.lat, lng: walk.stop.lng }) : null);

  const secondsRemaining =
    p && p.total > 0 && walk?.durationSeconds
      ? (walk.durationSeconds * p.remaining) / p.total
      : null;

  return {
    location,
    walk,
    active,
    busy,
    error,
    start,
    stop: stopNav,
    remaining,
    offRoute,
    maneuver,
    secondsRemaining,
  };
}
