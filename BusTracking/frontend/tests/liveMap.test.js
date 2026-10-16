import test from 'node:test';
import assert from 'node:assert/strict';
import {
  busFreshness, etaText, isRegional, regionLabel, DEFAULT_REGION, approachingBuses, distanceText, learningText,
  groupStands, arrivalsByRoute,
} from '../src/utils/liveMap.js';

test('GPS freshness: live, delayed and stale are distinguished by age', () => {
  assert.equal(busFreshness({ lastUpdatedEpochSeconds: 1000 }, 1010), 'LIVE');
  assert.equal(busFreshness({ lastUpdatedEpochSeconds: 1000 }, 1030), 'LIVE');
  assert.equal(busFreshness({ lastUpdatedEpochSeconds: 1000 }, 1031), 'DELAYED');
  assert.equal(busFreshness({ lastUpdatedEpochSeconds: 1000 }, 1120), 'DELAYED');
  assert.equal(busFreshness({ lastUpdatedEpochSeconds: 1000 }, 1121), 'STALE');
  assert.equal(busFreshness({}, 1000), 'STALE');
});

test('ETA text never fabricates a number', () => {
  assert.equal(etaText({ etaMinutes: null, availability: 'UNAVAILABLE' }), 'ETA unavailable');
  assert.equal(etaText(undefined), 'ETA unavailable');
  assert.equal(etaText({ etaMinutes: NaN }), 'ETA unavailable');
});

test('ETA text marks estimates with ≈ but not TripUpdates predictions', () => {
  assert.equal(etaText({ etaMinutes: 7.4, availability: 'TRIP_UPDATE' }), '7 min');
  assert.equal(etaText({ etaMinutes: 7.4, availability: 'STOP_CHAIN_ESTIMATE' }), '≈ 7 min');
  assert.equal(etaText({ etaMinutes: 0.3, availability: 'GEOMETRY_ESTIMATE' }), '≈ < 1 min');
});

test('default region is route-based North or North-West; ALL keeps legacy behaviour', () => {
  assert.equal(DEFAULT_REGION, 'ANY');
  assert.equal(isRegional('ANY'), true);
  assert.equal(isRegional('NORTH_WEST'), true);
  assert.equal(isRegional('ALL'), false);
  assert.equal(regionLabel('NORTH'), 'North Delhi routes');
});

test('approaching buses: one entry per vehicle at its soonest stop, positionless ones dropped', () => {
  const stops = [
    { id: 'a', name: 'Stop A', lat: 28.7, lng: 77.1, upcoming: [
      { vehicleId: 'v1', routeNumber: '205', etaMinutes: 9, busLatitude: 28.6, busLongitude: 77.0 },
      { vehicleId: 'v2', routeNumber: '990', etaMinutes: 3, busLatitude: 28.65, busLongitude: 77.05 },
      { vehicleId: 'v3', routeNumber: '101', etaMinutes: 1, busLatitude: null, busLongitude: null },
    ] },
    { id: 'b', name: 'Stop B', lat: 28.71, lng: 77.11, upcoming: [
      { vehicleId: 'v1', routeNumber: '205', etaMinutes: 6, busLatitude: 28.6, busLongitude: 77.0 },
    ] },
  ];
  const out = approachingBuses(stops);
  assert.deepEqual(out.map(b => [b.vehicleId, b.stopName]), [['v2', 'Stop A'], ['v1', 'Stop B']]);
  assert.equal(out[1].stopLat, 28.71);
  assert.deepEqual(approachingBuses(undefined), []);
});

test('typical-speed ETAs are marked as estimates; distances are readable', () => {
  assert.equal(etaText({ etaMinutes: 41.6, availability: 'TYPICAL_SPEED_ESTIMATE' }), '≈ 42 min');
  assert.equal(distanceText(850.4), '850 m');
  assert.equal(distanceText(12400), '12.4 km');
  assert.equal(distanceText(null), null);
});

test('learning progress text only appears once routes are running', () => {
  assert.equal(learningText(null), null);
  assert.equal(learningText({ routesRunningNow: 0, learnedOfRunning: 0 }), null);
  assert.equal(learningText({ routesRunningNow: 450, learnedOfRunning: 120 }),
    'Learned real paths for 120 of 450 routes running now');
  assert.equal(etaText({ etaMinutes: 3.3, availability: 'LEARNED_ROUTE_ESTIMATE' }), '≈ 3 min');
});

test('stops with the same name on both sides of a road become one stand; far namesakes do not', () => {
  const up = (vehicleId, routeNumber, etaMinutes) => ({ vehicleId, routeNumber, etaMinutes });
  const stands = groupStands([
    { id: 'a1', name: 'Madhuban Chowk', lat: 28.7030, lng: 77.1316, distanceMeters: 130,
      routeNumbers: ['141', '174'], upcoming: [up('v1', '141', 6)] },
    { id: 'a2', name: 'madhuban chowk ', lat: 28.7031, lng: 77.1318, distanceMeters: 135,
      routeNumbers: ['141', '990'], upcoming: [up('v1', '141', 4), up('v2', '990', 9)] },
    { id: 'far', name: 'Madhuban Chowk', lat: 28.7052, lng: 77.1316, distanceMeters: 370,
      routeNumbers: ['141'], upcoming: [] },
  ]);
  assert.equal(stands.length, 2, 'the namesake ~235 m away stays a separate stand');
  assert.equal(stands[0].id, 'a1', 'nearest stop represents the stand');
  assert.deepEqual(stands[0].members.map(m => m.id), ['a1', 'a2']);
  assert.deepEqual(stands[0].routeNumbers.sort(), ['141', '174', '990']);
  assert.equal(stands[0].liveCount, 2);
  assert.equal(stands[0].upcoming.find(u => u.vehicleId === 'v1').etaMinutes, 4, 'one entry per bus, soonest');
});

test('arrivals are grouped by route, soonest route first, idle routes listed separately', () => {
  const { tracked, idle } = arrivalsByRoute({
    routeNumbers: ['990', '141', '174', '10', '2'],
    upcoming: [
      { vehicleId: 'a', routeNumber: '141', etaMinutes: 12 },
      { vehicleId: 'b', routeNumber: '990', etaMinutes: 3 },
      { vehicleId: 'c', routeNumber: '141', etaMinutes: 5 },
    ],
  });
  assert.deepEqual(tracked.map(g => g.routeNumber), ['990', '141']);
  assert.deepEqual(tracked[1].buses.map(b => b.vehicleId), ['c', 'a']);
  assert.deepEqual(idle, ['2', '10', '174']);
});

test('buses of one route waiting at the same spot are one entry with a count', () => {
  const { tracked } = arrivalsByRoute({
    routeNumbers: ['141'],
    upcoming: [
      { vehicleId: 'a', routeNumber: '141', etaMinutes: 22.1, distanceMeters: 6400 },
      { vehicleId: 'b', routeNumber: '141', etaMinutes: 22.3, distanceMeters: 6420 },
      { vehicleId: 'c', routeNumber: '141', etaMinutes: 22.2, distanceMeters: 6410 },
      { vehicleId: 'd', routeNumber: '141', etaMinutes: 9, distanceMeters: 2500 },
    ],
  });
  assert.deepEqual(tracked[0].buses.map(b => [b.vehicleId, b.count]), [['d', 1], ['a', 3]]);
});
