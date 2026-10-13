import { useEffect, useRef } from 'react';
import { MapContainer, TileLayer, Marker, Popup, Polyline, Circle, ZoomControl, useMap } from 'react-leaflet';
import { userIcon, stopIconFor, busIcon } from '../utils/leafletIcons';
import WheelchairBadge from './WheelchairBadge';

const DELHI_CENTER = [28.6139, 77.209];

function RecenterOnFirstFix({ userLocation }) {
  const map = useMap();
  const centered = useRef(false);

  useEffect(() => {
    if (userLocation && !centered.current) {
      map.setView([userLocation.lat, userLocation.lng], 14);
      centered.current = true;
    }
  }, [userLocation, map]);

  return <button type="button" disabled={!userLocation} onClick={() => map.setView([userLocation.lat, userLocation.lng], 16)}
    style={{ position: 'absolute', top: 12, right: 12, zIndex: 1000 }}>Center on My Location</button>;
}

export default function BusMap({ userLocation, buses, selectedRoute, onSelectStop, selectedStop, walkingRoute, routeMap }) {
  const busesAtStop = (stopName) => buses.filter((b) => b.stopName === stopName);

  // The API returns one row per (bus, nearby stop) pair - the same vehicle can
  // legitimately appear once per stop it's approaching. Collapse to one
  // marker per vehicle (closest match wins, since `buses` arrives pre-sorted
  // by distanceToUser) so React doesn't choke on duplicate keys.
  const uniqueBuses = Array.from(new Map(buses.map((b) => [b.vehicleId, b])).values()).filter(
    (b) => Number.isFinite(b.busLatitude) && Number.isFinite(b.busLongitude)
  );

  return (
    <MapContainer center={DELHI_CENTER} zoom={13} zoomControl={false} style={{ height: '100%', width: '100%' }}>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <ZoomControl position="bottomright" />

      <RecenterOnFirstFix userLocation={userLocation} />

      {userLocation && (
        <Marker position={[userLocation.lat, userLocation.lng]} icon={userIcon}>
          <Popup>You are here</Popup>
        </Marker>
      )}

      {userLocation?.accuracy && <Circle center={[userLocation.lat, userLocation.lng]} radius={userLocation.accuracy} pathOptions={{ color: "#3388ff", weight: 1 }} />}
      {selectedStop && <Marker position={[selectedStop.lat, selectedStop.lng]} icon={stopIconFor(selectedStop.name)}><Popup>{selectedStop.name}</Popup></Marker>}
      {walkingRoute?.available && <Polyline positions={walkingRoute.coordinates} pathOptions={{ color: "#16835b", weight: 5 }} />}

      {routeMap?.shapes?.map((points, i) => <Polyline key={i} positions={points.map(p => [p.lat, p.lng])} pathOptions={{ color: "#aa3bff", weight: 3 }} />)}
      {routeMap?.stops?.filter(s => s.id !== selectedStop?.id).map(s => <Marker key={s.id} position={[s.lat, s.lng]} icon={stopIconFor(s.name)} eventHandlers={{ click: () => onSelectStop?.(s) }}><Popup>{s.name}</Popup></Marker>)}

      {uniqueBuses.map((bus) => (
        <Marker
          key={bus.vehicleId}
          position={[bus.busLatitude, bus.busLongitude]}
          icon={busIcon(bus.routeCode)}
        >
          <Popup>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', marginBottom: '4px' }}>
              <strong>Route {bus.routeCode}</strong>
              <WheelchairBadge status={bus.wheelchairSpaceAvailable} showLabel={false} size="sm" />
            </div>
            {bus.routeName ? `${bus.routeName}` : ''}
            <div style={{ margin: '4px 0' }}>
              <WheelchairBadge status={bus.wheelchairSpaceAvailable} showLabel={true} size="sm" />
            </div>
            Vehicle: {bus.vehicleId}
            <div>Position: {bus.busLatitude.toFixed(5)}, {bus.busLongitude.toFixed(5)}</div>
            {bus.direction && <div>Direction: {bus.direction}</div>}
            {bus.lastUpdatedEpochSeconds && <div>GPS: {new Date(bus.lastUpdatedEpochSeconds * 1000).toLocaleTimeString()}</div>}
            {bus.lastUpdatedEpochSeconds && <div>Fix age: {Math.max(0, Math.floor(Date.now() / 1000 - bus.lastUpdatedEpochSeconds))} seconds</div>}
            <br />
            ETA: {bus.etaMinutes != null ? `${bus.etaMinutes} min` : 'N/A'}
            <br />
            {bus.stopName && (
              <>
                Next stop: {bus.stopName}
                <br />
              </>
            )}
            {bus.distanceToUser != null && <>Distance: {Math.round(bus.distanceToUser)} m</>}
          </Popup>
        </Marker>
      ))}

      {selectedRoute?.stops?.length > 0 && (
        <>
          <Polyline
            positions={selectedRoute.stops.map((s) => [s.lat, s.lng])}
            pathOptions={{ color: '#aa3bff', weight: 4 }}
          />
          {selectedRoute.stops.map((stop) => {
            const upcoming = busesAtStop(stop.name);
            return (
              <Marker
                key={stop.id}
                position={[stop.lat, stop.lng]}
                icon={stopIconFor(stop.name)}
                eventHandlers={{ click: () => onSelectStop?.(stop) }}
              >
                <Popup>
                  <strong>{stop.name}</strong>
                  <br />
                  {upcoming.length > 0 ? (
                    <>
                      Upcoming buses:
                      <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
                        {upcoming.map((b) => (
                          <li key={b.vehicleId}>
                            Route {b.routeCode} — {b.etaMinutes} min
                          </li>
                        ))}
                      </ul>
                    </>
                  ) : (
                    'No buses currently tracked at this stop'
                  )}
                </Popup>
              </Marker>
            );
          })}
        </>
      )}
    </MapContainer>
  );
}
