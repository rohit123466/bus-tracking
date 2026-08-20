import { useEffect, useState } from 'react';
import { getWeatherAlert } from '../api/client';

const POLL_MS = 5 * 60 * 1000; // backend itself caches for 10 min; this just checks for updates

export default function WeatherAlertBanner() {
  const [weather, setWeather] = useState(null);
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    let cancelled = false;

    const fetchWeather = () => {
      getWeatherAlert()
        .then((data) => {
          if (cancelled) return;
          setWeather((prev) => {
            if (prev && !prev.raining && data.raining) setDismissed(false);
            return data;
          });
        })
        .catch(() => {});
    };

    fetchWeather();
    const interval = setInterval(fetchWeather, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  if (!weather || !weather.raining || dismissed) return null;

  return (
    <div className="weather-banner">
      <span>
        🌧️ It's {weather.condition} in Delhi right now — buses may run behind schedule, plan extra
        time.
      </span>
      <button onClick={() => setDismissed(true)} aria-label="Dismiss">
        ✕
      </button>
    </div>
  );
}
