import test from 'node:test';
import assert from 'node:assert/strict';
import {
  distanceKmText, gpsStale, locationText, mapBuses, quietRoutes, scheduledInText, SEARCH_PLACEHOLDER, standOption,
  standSummary, statusMeta,
} from '../src/utils/stands.js';

const NOW = 1_800_000_000;

test('search placeholder matches the requested wording', () => {
  assert.equal(SEARCH_PLACEHOLDER, 'Search your bus stand (e.g. Kashmere Gate, Azadpur, Rohini Sector 18)');
});

test('suggestions show stop count and routes in natural order, truncated', () => {
  const o = standOption({ id: 'b1', name: 'Azadpur Terminal', stopIds: ['b1', 'b2'], routeNumbers: ['234', '100', '166A', '166', '901', '990'] });
  assert.equal(o.id, 'b1');
  assert.equal(o.label, 'Azadpur Terminal');
  assert.equal(o.sub, '2 stops · Routes 100, 166, 166A, 234, 901 +1 more');
  assert.equal(standOption({ id: 'x', name: 'X', stopIds: ['x'], routeNumbers: ['7'] }).sub, 'Route 7');
  assert.equal(standOption({ id: 'y', name: 'Y', stopIds: ['y'], routeNumbers: [] }).sub, 'No routes in the timetable');
  // Two different stands with one name are told apart by the backend's hint.
  assert.equal(standOption({ id: 'z', name: 'Rohini Sec 18', stopIds: ['z'], routeNumbers: ['7'], hint: 'near Rohini Sec 18 Block' }).sub,
    'near Rohini Sec 18 Block · Route 7');
});

test('every status has a text label; unknown values are not passed off as one of them', () => {
  assert.equal(statusMeta('ARRIVING').label, 'Arriving');
  assert.equal(statusMeta('APPROACHING').label, 'Approaching');
  assert.equal(statusMeta('ON_THE_WAY').label, 'On the way');
  assert.equal(statusMeta('DELAYED').label, 'Delayed');
  assert.equal(statusMeta('SOMETHING').label, 'Unknown');
});

test('distance in km and location text never invent a value', () => {
  assert.equal(distanceKmText({ distanceKm: 0.49 }), '0.49 km');
  assert.equal(distanceKmText({ distanceKm: 12.345 }), '12.3 km');
  assert.equal(distanceKmText({ distanceKm: null }), null);
  assert.equal(locationText({ currentLocation: 'Near Azadpur Metro Station', busLatitude: 28.7, busLongitude: 77.1 }), 'Near Azadpur Metro Station');
  assert.equal(locationText({ busLatitude: 28.712345, busLongitude: 77.18 }), '28.7123, 77.1800');
  assert.equal(locationText({}), 'Location unavailable');
});

test('summary drops buses whose GPS went stale since the last refresh', () => {
  const data = {
    arrivals: [
      { vehicleId: 'a', status: 'ARRIVING', etaMinutes: 1, lastGpsUpdate: NOW - 20 },
      { vehicleId: 'b', status: 'APPROACHING', etaMinutes: 6, lastGpsUpdate: NOW - 200 }, // stale
      { vehicleId: 'c', status: 'ON_THE_WAY', etaMinutes: 25, lastGpsUpdate: NOW - 30 },
      { vehicleId: 'd', status: 'ON_THE_WAY', etaMinutes: null, lastGpsUpdate: NOW - 30 },
    ],
  };
  const s = standSummary(data, NOW);
  assert.equal(s.live, 3);
  assert.equal(s.approaching, 1);
  assert.equal(s.next.vehicleId, 'a');
  assert.equal(gpsStale({ lastGpsUpdate: NOW - 121 }, NOW), true);
  assert.equal(gpsStale({ lastGpsUpdate: 0 }, NOW), true);
  assert.deepEqual(standSummary(undefined, NOW), { live: 0, approaching: 0, next: null });
});

test('a bus ending its trip at the stand is not counted as the next or an approaching bus', () => {
  const s = standSummary({
    arrivals: [
      { vehicleId: 'ends', status: 'ARRIVING', etaMinutes: 0, lastGpsUpdate: NOW, terminatesHere: true },
      { vehicleId: 'goes', status: 'APPROACHING', etaMinutes: 5, lastGpsUpdate: NOW, terminatesHere: false },
    ],
  }, NOW);
  assert.equal(s.live, 2);
  assert.equal(s.approaching, 1);
  assert.equal(s.next.vehicleId, 'goes');
});

test('next bus is the first one with a time, not one without', () => {
  const s = standSummary({ arrivals: [{ vehicleId: 'x', status: 'ON_THE_WAY', etaMinutes: null, lastGpsUpdate: NOW }, { vehicleId: 'y', status: 'APPROACHING', etaMinutes: 4, lastGpsUpdate: NOW }] }, NOW);
  assert.equal(s.next.vehicleId, 'y');
});

test('map shows only buses with a live position, in the bus layer shape', () => {
  const buses = mapBuses([
    { vehicleId: 'a', routeNumber: '100', busLatitude: 28.7, busLongitude: 77.1, lastGpsUpdate: NOW, routeCheck: 'ON_ROUTE' },
    { vehicleId: 'b', routeNumber: '200', busLatitude: null, busLongitude: null, lastGpsUpdate: NOW },
  ]);
  assert.deepEqual(buses, [{ vehicleId: 'a', routeCode: '100', busLatitude: 28.7, busLongitude: 77.1, lastUpdatedEpochSeconds: NOW, routeCheck: 'ON_ROUTE', likelyRoutes: [] }]);
});

test('scheduled countdown comes from the timetable time and the clock', () => {
  assert.equal(scheduledInText({ scheduledEpochSeconds: NOW + 12 * 60 }, NOW), 'in 12 min');
  assert.equal(scheduledInText({ scheduledEpochSeconds: NOW - 30 }, NOW), 'due now');
  assert.equal(scheduledInText({}, NOW), '');
});

test('quiet routes are those with neither a live bus nor a timetable departure', () => {
  const data = {
    routeNumbers: ['100', '166', '234', '990'],
    arrivals: [{ routeNumber: '100' }],
    scheduled: [{ routeNumber: '234' }],
  };
  assert.deepEqual(quietRoutes(data), ['166', '990']);
});
