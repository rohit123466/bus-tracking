import { useEffect, useRef } from 'react';
import { MapContainer, TileLayer, Marker, Popup, Polyline, ZoomControl, useMap } from 'react-leaflet';
import { userIcon, stopIcon, busIcon } from '../utils/leafletIcons';
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

  return null;
}

export default function BusMap({ userLocation, buses, selectedRoute, onSelectStop }) {
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
                icon={stopIcon}
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
