import Icon from '../ui/Icon';
import { Notice } from '../ui/States';

/**
 * What to show instead of location-based content, for each geolocation state. `gps` comes from
 * useUserLocation(). Renders nothing once a position is available.
 */
export default function LocationPrompt({ gps, why = 'to find stops and buses near you', children }) {
  if (gps.location) return null;
  if (gps.status === 'checking') return null;
  if (gps.status === 'locating') {
    return <p role="status" className="row small muted"><Icon name="locate" size={16} /> Finding your location…</p>;
  }
  if (gps.status === 'unsupported') {
    return <Notice tone="info">This browser can&apos;t share a location. {children}</Notice>;
  }
  const denied = gps.status === 'denied';
  return (
    <div className="stack" style={{ gap: 8 }}>
      {gps.error && <Notice tone={denied ? 'warn' : 'error'} role="alert">{gps.error}</Notice>}
      {!gps.error && <p className="small muted" style={{ margin: 0 }}>Share your location {why}. It stays on this device.</p>}
      <div className="row wrap">
        <button type="button" className="btn btn-primary" onClick={gps.locate}>
          <Icon name="locate" size={18} /> {denied || gps.error ? 'Try again' : 'Use my location'}
        </button>
        {children}
      </div>
    </div>
  );
}
