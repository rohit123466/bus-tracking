// The compact form of /api/transit/buses/regional (?format=compact): the same buses and fields as
// the plain answer, sent as one array per field (see RegionalBusesCache.compact on the server).
// expandRegional turns it back into the plain shape, so nothing else in the app knows about it.

const SCALE = 100000; // positions are sent as round(degrees × 1e5)

/** Columns that map straight onto a bus field of the same name. */
const PLAIN = [
  'vehicleId', 'routeId', 'routeCode', 'direction', 'speedKmh', 'mappingSource', 'distanceFromShapeMeters',
  'regions', 'nextStopId', 'nextStopName', 'nextStopSource', 'nextStopDistanceMeters', 'nextStopEtaMinutes',
  'nextStopEtaBasis', 'routeCheck', 'likelyRoutes',
];

const byVehicleId = (a, b) => (a.vehicleId < b.vehicleId ? -1 : a.vehicleId > b.vehicleId ? 1 : 0);

/**
 * A compact answer as the plain one: { buses: [{ vehicleId, busLatitude, … }], warnings, … }.
 * An answer that is not compact (an older server, a test fixture) is returned unchanged.
 * Fields the server had no value for are absent from a bus, as in the plain answer.
 */
export function expandRegional(data) {
  if (!data || data.compact !== 1 || !data.cols) return data;
  const { cols, count, order, tripIdPrefixed, compact: _compact, ...rest } = data;
  const n = count ?? cols.vehicleId?.length ?? 0;
  const buses = new Array(n);
  for (let i = 0; i < n; i++) {
    const b = {};
    for (const key of PLAIN) {
      const v = cols[key]?.[i];
      if (v != null) b[key] = v;
    }
    let trip = cols.tripId?.[i];
    if (trip != null) {
      if (tripIdPrefixed && trip.charCodeAt(0) === 94 /* ^ */) trip = b.routeId + trip.slice(1);
      b.tripId = trip;
    }
    b.busLatitude = cols.lat[i] / SCALE;
    b.busLongitude = cols.lng[i] / SCALE;
    b.lastUpdatedEpochSeconds = rest.fetchedAt - cols.age[i];
    b.offRouteFlag = cols.offRouteFlag?.[i] === 1;
    buses[i] = b;
  }
  // The server groups buses by route for compression and says how they were ordered before.
  if (order === 'vehicleId') buses.sort(byVehicleId);
  return { ...rest, buses };
}
