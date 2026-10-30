import test from 'node:test';
import assert from 'node:assert/strict';
import {
  nearestRouteStop, towardsAt, routeArrivals, catchVerdict, walkMinutesFor, FAR_STOP_METERS,
  routeStandsNear, withRouteArrivals, NEAR_ROUTE_METERS,
} from '../src/utils/liveMap.js';
import { metresBetween } from '../src/utils/geo.js';

const here = { lat: 28.7000, lng: 77.1000 };
const stops = [
  { id: 'far', name: 'Far', lat: 28.7200, lng: 77.1000 },   // ≈ 2.2 km north
  { id: 'near', name: 'Near', lat: 28.7010, lng: 77.1000 }, // ≈ 110 m north
  { id: 'mid', name: 'Mid', lat: 28.7050, lng: 77.1000 },   // ≈ 560 m north
];

test('haversine distance: 0.001° of latitude is about 111 m', () => {
  assert.equal(metresBetween(here, here), 0);
  assert.ok(Math.abs(metresBetween(here, { lat: 28.701, lng: 77.1 }) - 111) < 1);
});

test('nearest route stop: smallest straight-line distance wins, with that distance', () => {
  const best = nearestRouteStop({ source: 'STOPS_ONLY', stops }, here);
  assert.equal(best.stop.id, 'near');
  assert.ok(Math.abs(best.distanceMeters - 111) < 1);
});

test('nearest route stop: none without a fix, stops, or usable coordinates', () => {
  assert.equal(nearestRouteStop({ stops }, null), null);
  assert.equal(nearestRouteStop({ stops: [] }, here), null);
  assert.equal(nearestRouteStop(undefined, here), null);
  assert.equal(nearestRouteStop({ stops: [{ id: 'x', lat: null, lng: 77.1 }] }, here), null);
  const best = nearestRouteStop({ stops: [{ id: 'x', lat: NaN, lng: 77.1 }, stops[0]] }, here);
  assert.equal(best.stop.id, 'far');
});

test('a far nearest stop is still returned, beyond the far threshold', () => {
  const best = nearestRouteStop({ stops: [stops[0]] }, here);
  assert.equal(best.stop.id, 'far');
  assert.ok(best.distanceMeters > FAR_STOP_METERS);
});

test('towards: directions whose pattern visits the stop (or another stop of its stand), each once', () => {
  const geometry = {
    source: 'PATTERNS',
    patterns: [
      { routeId: 'u', direction: 'UP', towards: 'Azadpur', stops: [{ id: 'a' }, { id: 'near' }] },
      { routeId: 'd', direction: 'DOWN', towards: 'Rohini', stops: [{ id: 'opposite' }, { id: 'a' }] },
      { routeId: 'u2', direction: 'UP', towards: 'Azadpur', stops: [{ id: 'near' }] },
    ],
  };
  assert.deepEqual(towardsAt(geometry, ['near']), ['Azadpur']);
  assert.deepEqual(towardsAt(geometry, ['near', 'opposite']), ['Azadpur', 'Rohini']);
  assert.deepEqual(towardsAt(geometry, ['elsewhere']), []);
  assert.deepEqual(towardsAt({ source: 'STOPS_ONLY', patterns: [], stops }, ['near']), []);
});

test('route arrivals: only this route, soonest first, unknown times last, stale GPS dropped', () => {
  const now = 10_000;
  const upcoming = [
    { vehicleId: 'v1', routeNumber: '901', etaMinutes: 12, lastGpsUpdate: now - 20 },
    { vehicleId: 'v2', routeNumber: '534', etaMinutes: 2, lastGpsUpdate: now - 20 },
    { vehicleId: 'v3', routeNumber: '901', etaMinutes: null, availability: 'UNAVAILABLE', lastGpsUpdate: now - 20 },
    { vehicleId: 'v4', routeNumber: '901', etaMinutes: 4, lastGpsUpdate: now - 20 },
    { vehicleId: 'v5', routeNumber: '901', etaMinutes: 1, lastGpsUpdate: now - 300 }, // GPS 5 min old
  ];
  assert.deepEqual(routeArrivals(upcoming, '901', now).map(u => u.vehicleId), ['v4', 'v1', 'v3']);
  assert.deepEqual(routeArrivals(upcoming, ' 534 ', now).map(u => u.vehicleId), ['v2']);
  assert.deepEqual(routeArrivals(upcoming, '999', now), []);
  assert.deepEqual(routeArrivals(undefined, '901', now), []);
});

test('route arrivals match route numbers case-insensitively', () => {
  const upcoming = [{ vehicleId: 'v1', routeNumber: '990a', etaMinutes: 3, lastGpsUpdate: 100 }];
  assert.equal(routeArrivals(upcoming, '990A', 110).length, 1);
});

test('walk estimate: about 4.8 km/h, at least a minute, none without a distance', () => {
  assert.equal(walkMinutesFor(800), 10);
  assert.equal(walkMinutesFor(10), 1);
  assert.equal(walkMinutesFor(null), null);
  assert.equal(walkMinutesFor(NaN), null);
});

test('catch verdict compares the bus ETA with the walk, with a 2 min margin', () => {
  assert.equal(catchVerdict(10, 5), 'make');
  assert.equal(catchVerdict(7, 5), 'make');
  assert.equal(catchVerdict(6, 5), 'leave-now');
  assert.equal(catchVerdict(5, 5), 'leave-now');
  assert.equal(catchVerdict(4, 5), 'miss');
  assert.equal(catchVerdict(null, 5), null);
  assert.equal(catchVerdict(5, null), null);
});

test('route stands near: stops within 1.5 km, nearest first, both road sides merged into one stand', () => {
  const geometry = {
    stops: [
      ...stops,
      { id: 'near-opp', name: 'near', lat: 28.7011, lng: 77.1003 }, // other side of the road, same name
      { id: 'bad', name: 'Bad', lat: null, lng: 77.1 },
    ],
  };
  const near = routeStandsNear(geometry, here);
  assert.equal(NEAR_ROUTE_METERS, 1500);
  assert.deepEqual(near.map(s => s.name), ['Near', 'Mid']); // 'Far' is ≈ 2.2 km away
  assert.deepEqual(near[0].members.map(m => m.id), ['near', 'near-opp']);
  assert.deepEqual(routeStandsNear(geometry, here, 300).map(s => s.name), ['Near']);
  assert.deepEqual(routeStandsNear(geometry, null), []);
  assert.deepEqual(routeStandsNear(undefined, here), []);
});

test('route arrivals per stand: merged across its stops, one entry per bus, null outside coverage', () => {
  const now = 10_000;
  const stands = [
    { id: 'near', name: 'Near', members: [{ id: 'near' }, { id: 'near-opp' }] },
    { id: 'mid', name: 'Mid', members: [{ id: 'mid' }] },
    { id: 'out', name: 'Outside', members: [{ id: 'out' }] },
  ];
  const regional = [
    { id: 'near', upcoming: [
      { vehicleId: 'v1', routeNumber: '901', etaMinutes: 6, lastGpsUpdate: now },
      { vehicleId: 'v9', routeNumber: '534', etaMinutes: 1, lastGpsUpdate: now }, // another route
    ] },
    { id: 'near-opp', upcoming: [
      { vehicleId: 'v1', routeNumber: '901', etaMinutes: 4, lastGpsUpdate: now }, // same bus, sooner here
      { vehicleId: 'v2', routeNumber: '901', etaMinutes: 2, lastGpsUpdate: now },
    ] },
    { id: 'mid', upcoming: [] },
  ];
  const [near, mid, out] = withRouteArrivals(stands, regional, '901', now);
  assert.deepEqual(near.arrivals.map(u => [u.vehicleId, u.etaMinutes]), [['v2', 2], ['v1', 4]]);
  assert.deepEqual(mid.arrivals, []);
  assert.equal(out.arrivals, null);
  assert.deepEqual(withRouteArrivals([], regional, '901', now), []);
  assert.equal(withRouteArrivals(stands, undefined, '901', now)[0].arrivals, null);
});
