/**
 * BusMap — Leaflet map with live bus markers, route overlays, and stop markers.
 *
 * Enhanced for Feature 6 (Live Bus Progress):
 * - selectedBusId highlights a specific bus with a larger marker
 * - Popup now shows speed, direction, GPS age, route ID
 * - onSelectBus callback fires when a marker is clicked
 */
import { useEffect, useRef } from 'react';
import {
  MapContainer, TileLayer, Marker, Popup, Polyline,
  Circle, ZoomControl, useMap,
} from 'react-leaflet';
import { userIcon, stopIconFor, busIcon, selectedBusIcon } from '../utils/leafletIcons';
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
  return (
    <button
      type="button"
      disabled={!userLocation}
      onClick={() => userLocation && map.setView([userLocation.lat, userLocation.lng], 16)}
      style={{ position: 'absolute', top: 12, right: 12, zIndex: 1000 }}
    >
      Center on My Location
    </button>
  );
}

function formatAge(epochSeconds) {
  if (!epochSeconds) return 'unknown';
  const s = Math.floor(Date.now() / 1000 - epochSeconds);
  if (s < 10) return 'just now';
  if (s < 60) return `${s}s ago`;
  return `${Math.floor(s / 60)}m ${s % 60}s ago`;
}

export default function BusMap({
  userLocation,
  buses,
  selectedRoute,
  onSelectStop,
  onSelectBus,
  selectedStop,
  selectedBusId,   // vehicleId to highlight (Feature 6)
  walkingRoute,
  routeMap,
}) {
  const busesAtStop = (stopName) => buses.filter((b) => b.stopName === stopName);

  // Deduplicate by vehicleId — keep first (nearest / lowest alphabetically)
  const uniqueBuses = Array.from(new Map(buses.map((b) => [b.vehicleId, b])).values())
    .filter((b) => Number.isFinite(b.busLatitude) && Number.isFinite(b.busLongitude));

  return (
    <MapContainer center={DELHI_CENTER} zoom={13} zoomControl={false} style={{ height: '100%', width: '100%' }}>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <ZoomControl position="bottomright" />
      <RecenterOnFirstFix userLocation={userLocation} />

      {/* User location */}
      {userLocation && (
        <Marker position={[userLocation.lat, userLocation.lng]} icon={userIcon}>
          <Popup>You are here</Popup>
        </Marker>
      )}
      {userLocation?.accuracy && (
        <Circle
          center={[userLocation.lat, userLocation.lng]}
          radius={userLocation.accuracy}
          pathOptions={{ color: '#3388ff', weight: 1 }}
        />
      )}

      {/* Selected boarding stop */}
      {selectedStop && (
        <Marker position={[selectedStop.lat, selectedStop.lng]} icon={stopIconFor(selectedStop.name)}>
          <Popup>{selectedStop.name}</Popup>
        </Marker>
      )}

      {/* Walking route overlay */}
      {walkingRoute?.available && (
        <Polyline positions={walkingRoute.coordinates} pathOptions={{ color: '#16835b', weight: 5 }} />
      )}

      {/* Route map shapes + stop markers */}
      {routeMap?.shapes?.map((points, i) => (
        <Polyline
          key={i}
          positions={points.map(p => [p.lat, p.lng])}
          pathOptions={{ color: '#aa3bff', weight: 3 }}
        />
      ))}
      {routeMap?.stops?.filter(s => s.id !== selectedStop?.id).map(s => (
        <Marker
          key={s.id}
          position={[s.lat, s.lng]}
          icon={stopIconFor(s.name)}
          eventHandlers={{ click: () => onSelectStop?.(s) }}
        >
          <Popup>{s.name}</Popup>
        </Marker>
      ))}

      {/* Live bus markers */}
      {uniqueBuses.map((bus) => {
        const isSelected = bus.vehicleId === selectedBusId;
        return (
          <Marker
            key={bus.vehicleId}
            position={[bus.busLatitude, bus.busLongitude]}
            icon={isSelected ? (selectedBusIcon?.(bus.routeCode) ?? busIcon(bus.routeCode)) : busIcon(bus.routeCode)}
            eventHandlers={{ click: () => onSelectBus?.(bus) }}
            zIndexOffset={isSelected ? 1000 : 0}
          >
            <Popup>
              {/* Header row */}
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 4 }}>
                <strong>Route {bus.routeCode}</strong>
                {bus.wheelchairSpaceAvailable != null && (
                  <WheelchairBadge status={bus.wheelchairSpaceAvailable} showLabel={false} size="sm" />
                )}
              </div>

              {/* Route name if available (legacy path) */}
              {bus.routeName && <div style={{ fontSize: 12, marginBottom: 3 }}>{bus.routeName}</div>}

              <div><strong>Vehicle:</strong> {bus.vehicleId}</div>
              {bus.direction && <div><strong>Direction:</strong> {bus.direction}</div>}

              {/* Speed */}
              {bus.speedKmh != null
                ? <div><strong>Speed:</strong> {Math.round(bus.speedKmh)} km/h</div>
                : bus.speedKmh === 0 && <div><strong>Speed:</strong> Stopped</div>
              }

              {/* GPS update age */}
              <div><strong>Last GPS:</strong> {formatAge(bus.lastUpdatedEpochSeconds)}</div>

              {/* ETA — only for legacy nearby-bus responses that carry etaMinutes */}
              {bus.etaMinutes != null && (
                <div><strong>ETA:</strong> {bus.etaMinutes} min</div>
              )}

              {/* Stop name — only for legacy path */}
              {bus.stopName && <div><strong>Next stop:</strong> {bus.stopName}</div>}

              {/* Distance from user — only for nearby path */}
              {bus.distanceMeters != null && (
                <div><strong>Distance:</strong> {Math.round(bus.distanceMeters)} m</div>
              )}
              {bus.distanceToUser != null && (
                <div><strong>Distance:</strong> {Math.round(bus.distanceToUser)} m</div>
              )}

              {bus.wheelchairSpaceAvailable != null && (
                <div style={{ marginTop: 4 }}>
                  <WheelchairBadge status={bus.wheelchairSpaceAvailable} showLabel size="sm" />
                </div>
              )}
            </Popup>
          </Marker>
        );
      })}

      {/* Legacy selectedRoute overlay (RouteDetailPage etc.) */}
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
