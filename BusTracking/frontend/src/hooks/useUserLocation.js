import { useEffect, useState } from 'react';

// A single GPS subscription belongs to the page, independently of walking directions.
export default function useUserLocation() {
  const [location, setLocation] = useState(null);
  const [error, setError] = useState('');
  useEffect(() => {
    let disposed = false;
    if (!navigator.geolocation) {
      setError('Geolocation is not supported by this browser. Showing Delhi.');
      return;
    }
    let refreshing = false;
    const success = position => {
      refreshing = false;
      if (disposed) return;
      setLocation({ lat: position.coords.latitude, lng: position.coords.longitude,
        accuracy: position.coords.accuracy, timestamp: position.timestamp });
      setError('');
    };
    const failure = failure => {
      refreshing = false;
      if (disposed) return;
      setError(failure.code === 1
        ? 'GPS permission denied. Enable location permission in your browser. Showing Delhi until a location is available.'
        : 'Unable to obtain GPS location. Waiting for a new fix.');
    };
    const options = { enableHighAccuracy: true, maximumAge: 3000, timeout: 15000 };
    const watch = navigator.geolocation.watchPosition(success, failure, options);
    // Stationary devices may not emit watch events; obtain a new measured fix periodically.
    const timer = setInterval(() => {
      if (refreshing || !navigator.geolocation.getCurrentPosition) return;
      refreshing = true;
      navigator.geolocation.getCurrentPosition(success, failure, options);
    }, 15000);
    return () => { disposed = true; clearInterval(timer); navigator.geolocation.clearWatch(watch); };
  }, []);
  return { location, error };
}
