import test from 'node:test';
import assert from 'node:assert/strict';
import { expandRegional } from '../src/utils/regionalWire.js';

const compact = {
  compact: 1, warnings: ['w'], fetchedAt: 1000, feedAvailable: true, region: 'ANY',
  eligibleRouteCount: 2, excludedByRegion: 0, excludedOutsideArea: 1, count: 2, order: 'vehicleId', tripIdPrefixed: true,
  cols: {
    vehicleId: ['DL2', 'DL1'], routeId: ['72', '5'], routeCode: ['114', '102'], direction: ['UP', 'DOWN'],
    tripId: ['^_16_33', 'x9'], lat: [2870716, 2870398], lng: [7717364, 7719630], speedKmh: [12.5, null], age: [7, 20],
    mappingSource: ['ROUTE_ID_FALLBACK', 'ROUTE_ID_FALLBACK'], distanceFromShapeMeters: [null, null], offRouteFlag: [0, 1],
    regions: [['NORTH'], ['NORTH', 'NORTH_WEST']], nextStopId: ['221', null], nextStopName: ['Shalimar Bagh', null],
    nextStopSource: ['LEARNED_ROUTE_PATH', null], nextStopDistanceMeters: [598, null], nextStopEtaMinutes: [1.71, null],
    nextStopEtaBasis: ['LEARNED_ROUTE_ESTIMATE', null], routeCheck: ['ON_ROUTE', 'OFF_ROUTE'], likelyRoutes: [[], ['901']],
  },
};

test('compact /buses/regional answer expands to the plain shape, every bus kept', () => {
  const out = expandRegional(compact);
  assert.equal(out.buses.length, 2);
  assert.deepEqual(out.buses.map(b => b.vehicleId), ['DL1', 'DL2'], 'vehicleId order restored');
  const [one, two] = out.buses;
  assert.deepEqual(two, {
    vehicleId: 'DL2', routeId: '72', routeCode: '114', direction: 'UP', tripId: '72_16_33', speedKmh: 12.5,
    mappingSource: 'ROUTE_ID_FALLBACK', regions: ['NORTH'], nextStopId: '221', nextStopName: 'Shalimar Bagh',
    nextStopSource: 'LEARNED_ROUTE_PATH', nextStopDistanceMeters: 598, nextStopEtaMinutes: 1.71,
    nextStopEtaBasis: 'LEARNED_ROUTE_ESTIMATE', routeCheck: 'ON_ROUTE', likelyRoutes: [],
    busLatitude: 28.70716, busLongitude: 77.17364, lastUpdatedEpochSeconds: 993, offRouteFlag: false,
  });
  assert.equal(one.tripId, 'x9', 'a trip id not starting with ^ is kept as is');
  assert.equal(one.offRouteFlag, true);
  assert.equal(one.lastUpdatedEpochSeconds, 980);
  assert.ok(!('speedKmh' in one) && !('nextStopId' in one), 'missing values stay absent, like the plain answer');
  for (const k of ['warnings', 'fetchedAt', 'feedAvailable', 'region', 'eligibleRouteCount', 'excludedByRegion', 'excludedOutsideArea'])
    assert.deepEqual(out[k], compact[k], k);
  assert.ok(!('cols' in out) && !('compact' in out));
});

test('a plain answer (older server, fixtures) passes through untouched', () => {
  const plain = { buses: [{ vehicleId: 'a' }], fetchedAt: 1 };
  assert.equal(expandRegional(plain), plain);
  assert.equal(expandRegional(undefined), undefined);
});
