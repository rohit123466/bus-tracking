import { Circle, CircleMarker, Marker, Polyline, Tooltip } from 'react-leaflet';
import { busFreshness, routeUnconfirmed, shownRoute } from '../../utils/liveMap';
import { busPin, stopPin, mePin } from '../../utils/mapIcons';

/** Zoom from which buses are drawn as labelled route-number pins instead of dots. */
const DETAIL_ZOOM = 14;

/**
 * Dense-map strategy instead of clustering: below DETAIL_ZOOM every bus is a small canvas dot
 * (hundreds render cheaply and stay clickable); from DETAIL_ZOOM, labelled pins are drawn only for
 * buses in or near the viewport. The selected bus is always a labelled pin. Buses whose GPS fix is
 * older than 2 minutes never reach this layer (visibleBuses drops them).
 */
export function BusLayer({ buses, now, view, selectedId, onSelect, colors }) {
  const detailed = view.zoom >= DETAIL_ZOOM;
  const area = view.bounds?.pad(0.25);
  return buses.map(b => {
    const freshness = busFreshness(b, now);
    const selected = b.vehicleId === selectedId;
    const unconfirmed = routeUnconfirmed(b);
    const position = [b.busLatitude, b.busLongitude];
    const select = { click: () => onSelect(b) };
    const shown = shownRoute(b);
    const label = shown !== b.routeCode
      ? `Likely route ${shown} (feed says ${b.routeCode})`
      : `Route ${b.routeCode}${unconfirmed ? ' (unconfirmed)' : ''}`;
    if (!selected && !detailed) {
      const fill = freshness === 'LIVE' ? colors.live : colors.delayed;
      return (
        <CircleMarker
          key={b.vehicleId}
          center={position}
          radius={5}
          // Unconfirmed route: hollow ring instead of a filled dot.
          pathOptions={unconfirmed
            ? { color: colors.text, weight: 2, dashArray: '2 2', fillColor: colors.surface, fillOpacity: 1 }
            : { color: colors.surface, weight: 1.5, fillOpacity: 1, fillColor: fill }}
          eventHandlers={select}
        >
          <Tooltip direction="top" offset={[0, -4]}>{label}{freshness === 'DELAYED' ? ' · GPS delayed' : ''}</Tooltip>
        </CircleMarker>
      );
    }
    if (!selected && area && !area.contains(position)) return null;
    return (
      <Marker
        key={b.vehicleId}
        position={position}
        icon={busPin(shown, freshness, selected, unconfirmed, shown !== b.routeCode)}
        zIndexOffset={selected ? 2000 : 0}
        eventHandlers={select}
        title={label}
      />
    );
  });
}

/**
 * Route geometry. With ordered patterns, each direction is a line joining its stops in timetable
 * order (dashed: it is not the exact road path). Without them, stops only.
 */
export function RouteLayer({ geometry, colors, selectedStopId, onSelectStop }) {
  if (!geometry) return null;
  const colorOf = p => (p.direction === 'DOWN' ? colors.route1 : colors.route0);
  return (
    <>
      {geometry.patterns.map(p => (
        <Polyline
          key={p.routeId}
          positions={p.stops.map(s => [s.lat, s.lng])}
          pathOptions={{ color: colorOf(p), weight: 4, opacity: 0.8, dashArray: '8 6' }}
          interactive={false}
        />
      ))}
      {geometry.stops.filter(s => s.id !== selectedStopId).map(s => (
        <CircleMarker
          key={s.id}
          center={[s.lat, s.lng]}
          radius={5}
          pathOptions={{ color: colors.text, weight: 2, fillColor: colors.surface, fillOpacity: 1 }}
          eventHandlers={{ click: () => onSelectStop(s) }}
        >
          <Tooltip direction="top" offset={[0, -4]}>{s.name}</Tooltip>
        </CircleMarker>
      ))}
    </>
  );
}

/** Stops as focusable pins (keyboard: Tab, Enter). */
export function StopPins({ stops, selectedId, onSelect }) {
  return (stops ?? []).filter(s => s.id !== selectedId).map(s => (
    <Marker
      key={s.id}
      position={[s.lat, s.lng]}
      icon={stopPin(s.name)}
      title={s.name}
      eventHandlers={{ click: () => onSelect(s) }}
    />
  ));
}

export function SelectedStop({ stop }) {
  if (!stop) return null;
  return <Marker position={[stop.lat, stop.lng]} icon={stopPin(stop.name, true)} zIndexOffset={1500} title={stop.name} />;
}

export function UserMarker({ location }) {
  if (!location) return null;
  const pos = [location.lat, location.lng];
  return (
    <>
      {location.accuracy > 15 && (
        <Circle center={pos} radius={location.accuracy} interactive={false}
          pathOptions={{ color: '#1a73e8', weight: 1, fillOpacity: 0.08 }} />
      )}
      <Marker position={pos} icon={mePin} zIndexOffset={1800} title="Your location" keyboard={false} />
    </>
  );
}

/** Walking route: the pedestrian path when the server has one, else a dashed straight line. */
export function WalkLine({ walk, from, colors }) {
  if (!walk?.stop) return null;
  if (walk.available && walk.coordinates?.length) {
    return <Polyline positions={walk.coordinates} pathOptions={{ color: colors.walk, weight: 5, opacity: 0.85 }} interactive={false} />;
  }
  if (!from) return null;
  return (
    <Polyline
      positions={[[from.lat, from.lng], [walk.stop.lat, walk.stop.lng]]}
      pathOptions={{ color: colors.walk, weight: 3, dashArray: '2 8', opacity: 0.9 }}
      interactive={false}
    />
  );
}
