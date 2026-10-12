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
    const watch = navigator.geolocation.watchPosition(position => {
      if (disposed) return;
      setLocation({ lat: position.coords.latitude, lng: position.coords.longitude,
        accuracy: position.coords.accuracy, timestamp: position.timestamp });
      setError('');
    }, failure => {
      if (disposed) return;
      setError(failure.code === 1
        ? 'GPS permission denied. Enable location permission in your browser. Showing Delhi until a location is available.'
        : 'Unable to obtain GPS location. Waiting for a new fix.');
    }, { enableHighAccuracy: true, maximumAge: 3000, timeout: 15000 });
    return () => { disposed = true; navigator.geolocation.clearWatch(watch); };
  }, []);
  return { location, error };
}
