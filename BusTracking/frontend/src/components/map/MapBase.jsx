import { forwardRef, useEffect } from 'react';
import { MapContainer, TileLayer, ZoomControl, useMap, useMapEvents } from 'react-leaflet';

const DELHI_CENTER = [28.6139, 77.209];

/** Leaflet map with OSM tiles, bottom-right zoom buttons and resize handling. */
const BaseMap = forwardRef(function BaseMap({ children, center = DELHI_CENTER, zoom = 12 }, ref) {
  return (
    <MapContainer
      ref={ref}
      center={center}
      zoom={zoom}
      zoomControl={false}
      preferCanvas
      style={{ height: '100%', width: '100%' }}
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <ZoomControl position="bottomright" />
      <AutoResize />
      {children}
    </MapContainer>
  );
});

export default BaseMap;

/** Keeps tiles aligned when the container changes size (side panel collapsed, sheet resized). */
function AutoResize() {
  const map = useMap();
  useEffect(() => {
    const el = map.getContainer();
    let frame;
    const ro = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => map.invalidateSize({ pan: false }));
    });
    ro.observe(el);
    return () => { ro.disconnect(); cancelAnimationFrame(frame); };
  }, [map]);
  return null;
}

/** Reports { zoom, bounds } after every move/zoom so layers can adapt their rendering. */
export function ViewTracker({ onChange }) {
  const map = useMapEvents({
    moveend: () => onChange({ zoom: map.getZoom(), bounds: map.getBounds() }),
    zoomend: () => onChange({ zoom: map.getZoom(), bounds: map.getBounds() }),
  });
  useEffect(() => { onChange({ zoom: map.getZoom(), bounds: map.getBounds() }); }, [map, onChange]);
  return null;
}

/** Fits the given [lat, lng] points whenever `fitKey` changes. */
export function FitBounds({ points, fitKey, maxZoom = 16 }) {
  const map = useMap();
  useEffect(() => {
    if (!fitKey || !points?.length) return;
    if (points.length === 1) map.setView(points[0], Math.max(map.getZoom(), 15));
    else map.fitBounds(points, { padding: [40, 40], maxZoom });
    // Only refit when the subject changes, not on every refresh of the same points.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey, map]);
  return null;
}
