/**
 * useArrivalNotifications — foreground/browser-tab arrival alerts.
 *
 * Watches arrival data for a specific stop + route combination and fires
 * a browser Notification when the ETA crosses the user-set threshold.
 *
 * IMPORTANT LIMITATIONS (clearly stated to the user):
 * - These are FOREGROUND-ONLY notifications. They stop when the browser tab
 *   is closed or the app is unloaded.
 * - Background/push notifications would require a service-worker + Push API
 *   backend infrastructure that is NOT implemented here.
 * - Notifications require explicit browser permission (we ask only when the
 *   user opts in, never automatically).
 */
import { useCallback, useRef, useState } from 'react';

const SUPPORTED = typeof window !== 'undefined' && 'Notification' in window;

export default function useArrivalNotifications() {
  const [permission, setPermission] = useState(
    SUPPORTED ? Notification.permission : 'unsupported'
  );
  const [enabled, setEnabled] = useState(false);
  const [thresholdMinutes, setThresholdMinutes] = useState(5);
  const firedRef = useRef(new Set()); // dedup: Set<`${vehicleId}-${tripId}-${threshold}`>

  const requestPermission = useCallback(async () => {
    if (!SUPPORTED) return;
    const result = await Notification.requestPermission();
    setPermission(result);
    if (result === 'granted') setEnabled(true);
  }, []);

  const enable = useCallback(() => {
    if (permission === 'granted') { setEnabled(true); return; }
    requestPermission();
  }, [permission, requestPermission]);

  const disable = useCallback(() => {
    setEnabled(false);
    firedRef.current.clear();
  }, []);

  /**
   * Call this every time new arrivals data arrives for the watched stop/route.
   * @param {Array} arrivals — from /api/transit/stops/{id}/arrivals
   * @param {string} routeNumber — the route the user is watching (optional filter)
   * @param {string} stopName — display name for the notification body
   */
  const checkArrivals = useCallback((arrivals, routeNumber, stopName) => {
    if (!enabled || permission !== 'granted') return;
    const relevant = arrivals
      .filter(a => !routeNumber || a.routeNumber === routeNumber)
      .filter(a => a.etaMinutes != null && a.etaMinutes <= thresholdMinutes);

    for (const a of relevant) {
      const key = `${a.vehicleId}-${a.tripId}-${thresholdMinutes}`;
      if (firedRef.current.has(key)) continue;
      firedRef.current.add(key);

      try {
        new Notification(`Route ${a.routeNumber} arriving in ${Math.ceil(a.etaMinutes)} min`, {
          body: `Stop: ${stopName}\nVehicle: ${a.vehicleId} · ${a.predictionAvailability}`,
          icon: '/vite.svg',
          tag: key, // dedup at browser level too
        });
      } catch {
        // Notification API can throw in some browser configurations
      }
    }
  }, [enabled, permission, thresholdMinutes]);

  /**
   * Reset fired-set when the watched stop/route changes so we can re-notify
   * for the new combination.
   */
  const reset = useCallback(() => {
    firedRef.current.clear();
  }, []);

  // Also expose a way to cancel notifications for a specific vehicle
  const cancelFor = useCallback((vehicleId) => {
    for (const key of [...firedRef.current]) {
      if (key.startsWith(vehicleId + '-')) firedRef.current.delete(key);
    }
  }, []);

  return {
    permission,
    enabled,
    thresholdMinutes,
    setThresholdMinutes,
    enable,
    disable,
    checkArrivals,
    reset,
    cancelFor,
    supported: SUPPORTED,
    foregroundOnly: true, // clearly labelled — no background push
  };
}
