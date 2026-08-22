import { useEffect, useRef, useState } from 'react';
import { MapContainer, TileLayer, Marker, Polyline } from 'react-leaflet';
import { getBusHistory } from '../api/client';
import { vehicleIdIcon } from '../utils/leafletIcons';

const DELHI_CENTER = [28.6139, 77.209];

export default function ReplayPage() {
  const [vehicleId, setVehicleId] = useState('');
  const [points, setPoints] = useState([]);
  const [index, setIndex] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [error, setError] = useState(null);
  const intervalRef = useRef(null);

  const load = (e) => {
    e.preventDefault();
    setError(null);
    setPlaying(false);
    getBusHistory(vehicleId.trim())
      .then((data) => {
        if (data.length === 0) {
          setError('No recorded history for this vehicle yet.');
        }
        setPoints(data);
        setIndex(0);
      })
      .catch(() => setError('Failed to load journey history.'));
  };

  useEffect(() => {
    if (!playing) {
      clearInterval(intervalRef.current);
      return undefined;
    }
    intervalRef.current = setInterval(() => {
      setIndex((prev) => {
        if (prev >= points.length - 1) {
          setPlaying(false);
          return prev;
        }
        return prev + 1;
      });
    }, 400);
    return () => clearInterval(intervalRef.current);
  }, [playing, points.length]);

  const path = points.map((p) => [p.latitude, p.longitude]);
  const current = points[index];

  return (
    <div className="page-panel page-panel-wide">
      <h2>Journey Replay</h2>
      <form className="inline-form" onSubmit={load}>
        <input
          type="text"
          placeholder="Vehicle ID"
          value={vehicleId}
          onChange={(e) => setVehicleId(e.target.value)}
        />
        <button type="submit">Load</button>
      </form>

      {error && <div className="banner banner-error" style={{ position: 'static', marginTop: 12 }}>{error}</div>}

      {points.length > 0 && (
        <>
          <div className="inline-form" style={{ marginTop: 12 }}>
            <button type="button" onClick={() => setPlaying((p) => !p)}>
              {playing ? 'Pause' : 'Play'}
            </button>
            <input
              type="range"
              min={0}
              max={points.length - 1}
              value={index}
              onChange={(e) => setIndex(Number(e.target.value))}
              style={{ flex: 1 }}
            />
            <span>
              {index + 1} / {points.length}
              {current?.recordedAt ? ` — ${new Date(current.recordedAt).toLocaleTimeString()}` : ''}
              {current?.speedKmh != null ? ` — ${current.speedKmh.toFixed(1)} km/h` : ''}
            </span>
          </div>

          <div className="mini-map" style={{ marginTop: 12 }}>
            <MapContainer center={path[0] || DELHI_CENTER} zoom={14} style={{ height: '100%', width: '100%' }}>
              <TileLayer
                attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
              />
              <Polyline positions={path} pathOptions={{ color: '#aa3bff', weight: 3 }} />
              {current && <Marker position={[current.latitude, current.longitude]} icon={vehicleIdIcon(vehicleId)} />}
            </MapContainer>
          </div>
        </>
      )}
    </div>
  );
}
