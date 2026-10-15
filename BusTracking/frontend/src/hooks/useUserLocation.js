import { useCallback, useEffect, useState } from 'react';

const toLocation = position => ({ lat: position.coords.latitude, lng: position.coords.longitude,
  accuracy: position.coords.accuracy, timestamp: position.timestamp });

const failureMessage = failure => failure.code === 1
  ? 'GPS permission denied. Enable location permission in your browser. Showing Delhi until a location is available.'
  : 'Unable to obtain GPS location. Waiting for a new fix.';

// A single GPS subscription belongs to the page, independently of walking directions.
// The position is kept in memory only; nothing is persisted or sent anywhere by this hook.
export default function useUserLocation() {
  const [location, setLocation] = useState(null);
  const [error, setError] = useState('');
  const [locating, setLocating] = useState(false);
  useEffect(() => {
    let disposed = false;
    if (!navigator.geolocation) {
      setError('Geolocation is not supported by this browser. Showing Delhi.');
      return;
    }
    const watch = navigator.geolocation.watchPosition(position => {
      if (disposed) return;
      setLocation(toLocation(position));
      setError('');
    }, failure => {
      if (disposed) return;
      setError(failureMessage(failure));
    }, { enableHighAccuracy: true, maximumAge: 3000, timeout: 15000 });
    return () => { disposed = true; navigator.geolocation.clearWatch(watch); };
  }, []);

  // One-shot fix for the "Locate Me" button; re-prompts if permission was dismissed.
  const locate = useCallback(() => new Promise(resolve => {
    if (!navigator.geolocation) {
      setError('Geolocation is not supported by this browser. Showing Delhi.');
      resolve(null);
      return;
    }
    setLocating(true);
    navigator.geolocation.getCurrentPosition(position => {
      const next = toLocation(position);
      setLocation(next); setError(''); setLocating(false);
      resolve(next);
    }, failure => {
      setError(failureMessage(failure)); setLocating(false);
      resolve(null);
    }, { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 });
  }), []);

  return { location, error, locate, locating };
}
