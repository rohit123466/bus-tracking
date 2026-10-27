import { memo, useCallback, useEffect, useMemo, useRef } from 'react';
import { Circle, CircleMarker, Marker, Polyline, Tooltip } from 'react-leaflet';
import { busFreshness, routeUnconfirmed, shownRoute } from '../../utils/liveMap';
import { busPin, stopPin, mePin } from '../../utils/mapIcons';

/** Zoom from which buses are drawn as labelled route-number pins instead of dots. */
const DETAIL_ZOOM = 14;

// react-leaflet re-applies a prop whenever its identity changes (setLatLng for a new position
// array, setStyle for a new pathOptions object, off+on for a new eventHandlers object). The layers
// below keep those identities stable, so a refresh only touches the markers that actually changed.
const styles = new Map();
/** One shared, frozen pathOptions object per distinct style. */
function pathStyle(key, make) {
  let s = styles.get(key);
  if (!s) { s = Object.freeze(make()); styles.set(key, s); }
  return s;
}

const TOOLTIP_OFFSET = [0, -4];

/** Position array that keeps its identity until the coordinates change. */
const usePosition = (lat, lng) => useMemo(() => [lat, lng], [lat, lng]);

/** A stable event-handler object calling the latest `onClick`. */
function useClick(onClick, arg) {
  const latest = useRef(onClick);
  useEffect(() => { latest.current = onClick; });
  return useMemo(() => ({ click: () => latest.current(arg) }), [arg]);
}

const BusDot = memo(function BusDot({ id, lat, lng, live, unconfirmed, label, colors, onSelect }) {
  const center = usePosition(lat, lng);
  const handlers = useClick(onSelect, id);
  // Unconfirmed route: hollow ring instead of a filled dot.
  const style = unconfirmed
    ? pathStyle(`ring|${colors.text}|${colors.surface}`, () => ({ color: colors.text, weight: 2, dashArray: '2 2', fillColor: colors.surface, fillOpacity: 1 }))
    : pathStyle(`dot|${colors.surface}|${live ? colors.live : colors.delayed}`, () => ({ color: colors.surface, weight: 1.5, fillOpacity: 1, fillColor: live ? colors.live : colors.delayed }));
  return (
    <CircleMarker center={center} radius={5} pathOptions={style} eventHandlers={handlers}>
      <Tooltip direction="top" offset={TOOLTIP_OFFSET}>{label}</Tooltip>
    </CircleMarker>
  );
});

const BusPin = memo(function BusPin({ id, lat, lng, icon, selected, label, onSelect }) {
  const position = usePosition(lat, lng);
  const handlers = useClick(onSelect, id);
  return <Marker position={position} icon={icon} zIndexOffset={selected ? 2000 : 0} eventHandlers={handlers} title={label} />;
});

/**
 * Dense-map strategy instead of clustering: below DETAIL_ZOOM every bus is a small canvas dot
 * (hundreds render cheaply and stay clickable); from DETAIL_ZOOM, labelled pins are drawn only for
 * buses in or near the viewport. The selected bus is always a labelled pin. Buses whose GPS fix is
 * older than 2 minutes never reach this layer (visibleBuses drops them). Markers are keyed by
 * vehicleId and only receive primitive props, so a bus that did not move or change is skipped.
 */
export function BusLayer({ buses, now, view, selectedId, onSelect, colors }) {
  const detailed = view.zoom >= DETAIL_ZOOM;
  const area = view.bounds?.pad(0.25);
  const latest = useRef(onSelect);
  useEffect(() => { latest.current = onSelect; });
  const select = useCallback(id => latest.current(id), []);
  // Only the colours the dots use: a new `colors` object with the same values changes nothing.
  const dotColors = useMemo(
    () => ({ live: colors.live, delayed: colors.delayed, surface: colors.surface, text: colors.text }),
    [colors.live, colors.delayed, colors.surface, colors.text],
  );
  return buses.map(b => {
    const freshness = busFreshness(b, now);
    const selected = b.vehicleId === selectedId;
    const unconfirmed = routeUnconfirmed(b);
    const shown = shownRoute(b);
    const label = shown !== b.routeCode
      ? `Likely route ${shown} (feed says ${b.routeCode})`
      : `Route ${b.routeCode}${unconfirmed ? ' (unconfirmed)' : ''}`;
    if (!selected && !detailed) {
      return (
        <BusDot
          key={b.vehicleId} id={b.vehicleId} lat={b.busLatitude} lng={b.busLongitude}
          live={freshness === 'LIVE'} unconfirmed={unconfirmed} colors={dotColors} onSelect={select}
          label={`${label}${freshness === 'DELAYED' ? ' · GPS delayed' : ''}`}
        />
      );
    }
    if (!selected && area && !area.contains([b.busLatitude, b.busLongitude])) return null;
    return (
      <BusPin
        key={b.vehicleId} id={b.vehicleId} lat={b.busLatitude} lng={b.busLongitude} selected={selected}
        icon={busPin(shown, freshness, selected, unconfirmed, shown !== b.routeCode)} label={label} onSelect={select}
      />
    );
  });
}

/**
 * Route geometry. With ordered patterns, each direction is a line joining its stops in timetable
 * order (dashed: it is not the exact road path). Without them, stops only.
 */
export function RouteLayer({ geometry, colors, selectedStopId, onSelectStop }) {
  // Joined once per geometry, not on every map refresh.
  const lines = useMemo(
    () => (geometry?.patterns ?? []).map(p => ({ key: p.routeId, down: p.direction === 'DOWN', positions: p.stops.map(s => [s.lat, s.lng]) })),
    [geometry],
  );
  if (!geometry) return null;
  return (
    <>
      {lines.map(l => {
        const color = l.down ? colors.route1 : colors.route0;
        return (
          <Polyline
            key={l.key}
            positions={l.positions}
            pathOptions={pathStyle(`line|${color}`, () => ({ color, weight: 4, opacity: 0.8, dashArray: '8 6' }))}
            interactive={false}
          />
        );
      })}
      {geometry.stops.filter(s => s.id !== selectedStopId).map(s => (
        <RouteStop key={s.id} stop={s} text={colors.text} surface={colors.surface} onSelect={onSelectStop} />
      ))}
    </>
  );
}

const RouteStop = memo(function RouteStop({ stop, text, surface, onSelect }) {
  const center = usePosition(stop.lat, stop.lng);
  const handlers = useClick(onSelect, stop);
  return (
    <CircleMarker
      center={center}
      radius={5}
      pathOptions={pathStyle(`stop|${text}|${surface}`, () => ({ color: text, weight: 2, fillColor: surface, fillOpacity: 1 }))}
      eventHandlers={handlers}
    >
      <Tooltip direction="top" offset={TOOLTIP_OFFSET}>{stop.name}</Tooltip>
    </CircleMarker>
  );
});

/** Stops as focusable pins (keyboard: Tab, Enter). */
export function StopPins({ stops, selectedId, onSelect }) {
  return (stops ?? []).filter(s => s.id !== selectedId).map(s => (
    <StopPin key={s.id} stop={s} onSelect={onSelect} />
  ));
}

// The icon keeps its identity, so a refresh does not replace the pin's element (and drop the
// keyboard focus a Tab user put on it).
const StopPin = memo(function StopPin({ stop, onSelect }) {
  const position = usePosition(stop.lat, stop.lng);
  const handlers = useClick(onSelect, stop);
  return <Marker position={position} icon={stopPin(stop.name)} title={stop.name} eventHandlers={handlers} />;
});

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
