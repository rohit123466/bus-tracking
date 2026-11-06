import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_WALK_METERS, departuresText, distanceLabel, routeSearchError, intermediateStops, liveCountText, liveMapLink, parseWalk, pickupStateMeta,
  resultsSummary, routeMapBuses, sameStand, sortRoutes, timetableText, tripParams, validateTrip, walkNote, withoutStand,
} from '../src/utils/standRoutes.js';

const kg = { id: '267', name: 'ISBT Kashmere Gate', stopIds: ['267', '6026'] };
const r18 = { id: '517', name: 'Rohini Sec 18', stopIds: ['517', '7635'] };

const stop = (id, name) => ({ id, name, lat: 28.7, lng: 77.1 });
const route = (number, extra = {}) => ({
  key: number, routeNumber: number, liveBusCount: 0, scheduledMinutes: 30,
  stops: [stop('a', 'A'), stop('b', 'B'), stop('c', 'C')], ...extra,
});

test('both stands must be chosen, and must differ', () => {
  assert.deepEqual(validateTrip(kg, r18), {});
  assert.match(validateTrip(null, r18).from, /pick-up/);
  assert.match(validateTrip(kg, null).to, /destination/);
  const both = validateTrip(null, null);
  assert.ok(both.from && both.to);
  assert.match(validateTrip(kg, { ...kg }).to, /different/);
  // Another stop ID of the same stand is the same stand.
  assert.match(validateTrip(kg, { id: '6026', name: 'ISBT Kashmiri Gate' }).to, /different/);
});

test('sameStand compares any stop of the stand', () => {
  assert.equal(sameStand(kg, r18), false);
  assert.equal(sameStand(kg, { id: '6026' }), true);
  assert.equal(sameStand(null, kg), false);
});

test('suggestions leave out the stand chosen in the other field', () => {
  assert.deepEqual(withoutStand([kg, r18], kg), [r18]);
  assert.deepEqual(withoutStand([kg, r18], null), [kg, r18]);
  assert.deepEqual(withoutStand(undefined, kg), []);
});

test('sorting keeps unknown times last and never drops a route', () => {
  const routes = [
    route('120', { scheduledMinutes: null, liveBusCount: 3 }),
    route('100', { scheduledMinutes: 40 }),
    route('102', { scheduledMinutes: 25, stops: [stop('a', 'A'), stop('c', 'C')] }),
  ];
  assert.deepEqual(sortRoutes(routes, 'best').map(r => r.routeNumber), ['120', '100', '102']);
  assert.deepEqual(sortRoutes(routes, 'time').map(r => r.routeNumber), ['102', '100', '120']);
  assert.deepEqual(sortRoutes(routes, 'stops').map(r => r.routeNumber), ['102', '100', '120']);
  assert.deepEqual(sortRoutes(routes, 'live').map(r => r.routeNumber), ['120', '100', '102']);
  assert.equal(routes[0].routeNumber, '120'); // input untouched
});

test('intermediate stops exclude pick-up and destination', () => {
  assert.deepEqual(intermediateStops(route('100')).map(s => s.id), ['b']);
  assert.deepEqual(intermediateStops({ stops: [stop('a', 'A'), stop('c', 'C')] }), []);
});

test('distance says how it was measured', () => {
  assert.equal(distanceLabel({ distanceKm: 12.43, distanceBasis: 'ROUTE_SHAPE' }), '≈ 12 km by road');
  assert.equal(distanceLabel({ distanceKm: 4.26, distanceBasis: 'ROUTE_SHAPE' }), '≈ 4.3 km by road');
  assert.equal(distanceLabel({ distanceKm: 4.26, distanceBasis: 'STOP_TO_STOP' }), '≈ 4.3 km (straight lines between stops)');
  assert.equal(distanceLabel({ distanceKm: null }), null);
});

test('walk note only for stops outside the selected stand', () => {
  assert.equal(walkNote('ISBT Kashmere Gate', 127.4, false), '127 m walk from ISBT Kashmere Gate');
  assert.equal(walkNote('ISBT Kashmere Gate', 0, true), null);
});

test('timetable and live-bus wording', () => {
  assert.equal(timetableText({ tripsPerDay: 42, firstDeparture: '05:30', lastDeparture: '22:10' }), '42 trips a day · 05:30–22:10');
  assert.equal(timetableText({ tripsPerDay: 1, firstDeparture: '09:00', lastDeparture: '09:00' }), '1 trip a day · 09:00');
  assert.equal(timetableText({ tripsPerDay: 0 }), null);
  assert.equal(liveCountText({ liveBusCount: 0 }), 'No live buses currently available on this route');
  assert.equal(liveCountText({ liveBusCount: 1 }), '1 live bus on this route');
  assert.equal(liveCountText({ liveBusCount: 3 }), '3 live buses on this route');
  assert.equal(liveCountText({ liveBusCount: 3 }, false), 'Live feed unavailable');
  assert.equal(pickupStateMeta('PASSED_PICKUP').label, 'Already passed pick-up');
  assert.equal(pickupStateMeta('???').label, 'Position on route unknown');
});

test('URL params round-trip the walk setting', () => {
  assert.deepEqual(tripParams(kg, r18, DEFAULT_WALK_METERS), { from: '267', to: '517' });
  assert.deepEqual(tripParams(kg, r18, 0), { from: '267', to: '517', walk: '0' });
  assert.equal(parseWalk(null), DEFAULT_WALK_METERS);
  assert.equal(parseWalk('0'), 0);
  assert.equal(parseWalk('9999'), DEFAULT_WALK_METERS);
  assert.equal(parseWalk('abc'), DEFAULT_WALK_METERS);
});

test('live map links and map buses', () => {
  assert.equal(liveMapLink('100'), '/?route=100');
  assert.equal(liveMapLink('100', 'DL1PC 1234'), '/?route=100&bus=DL1PC+1234');
  const buses = routeMapBuses([
    { vehicleId: 'v1', routeNumber: '100', busLatitude: 28.7, busLongitude: 77.1, lastGpsUpdate: 5, routeCheck: 'ON_ROUTE' },
    { vehicleId: 'v2', routeNumber: '100', busLatitude: null, busLongitude: null },
  ]);
  assert.deepEqual(buses, [{
    vehicleId: 'v1', routeCode: '100', busLatitude: 28.7, busLongitude: 77.1, lastUpdatedEpochSeconds: 5,
    routeCheck: 'ON_ROUTE', likelyRoutes: [],
  }]);
});

test('results summary', () => {
  assert.equal(resultsSummary({ routes: [route('1'), route('2')], routesWithLiveBuses: 1 }), '2 direct routes · 1 with live buses now');
  assert.equal(resultsSummary({ routes: [] }), 'No direct route');
  assert.equal(resultsSummary({ routes: [], transfers: [{}, {}] }), 'No direct route · 2 journeys with 2 buses');
  assert.equal(resultsSummary({ routes: [route('1')], routesWithLiveBuses: 0, transfers: [{}] }), '1 direct route · 0 with live buses now · 1 journey with 2 buses');
});

test('departures after midnight (Delhi time) are marked tomorrow once', () => {
  // 2026-11-06 19:30 IST = 14:00 UTC.
  const now = Date.UTC(2026, 10, 6, 14, 0) / 1000;
  const at = (h, m, dayOffset = 0) => ({
    time: `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`,
    epochSeconds: Date.UTC(2026, 10, 6 + dayOffset, h, m) / 1000 - 5.5 * 3600,
  });
  assert.equal(departuresText([at(19, 56), at(8, 51, 1), at(10, 16, 1)], now), '19:56, tomorrow 08:51, 10:16');
  assert.equal(departuresText([at(20, 0), at(21, 0)], now), '20:00, 21:00');
  assert.equal(departuresText([], now), '');
});

test('a missing endpoint is not reported as an unknown bus stand', () => {
  assert.equal(routeSearchError({ kind: 'not-found', message: 'Unknown bus stop ID' }), 'unknown-stand');
  assert.equal(routeSearchError({ kind: 'not-found', message: 'No static resource api/transit/stands/routes.' }), 'unsupported');
  assert.equal(routeSearchError({ kind: 'not-found', message: 'Not found.' }), 'unsupported');
  assert.equal(routeSearchError({ kind: 'server', message: 'x' }), 'other');
});
