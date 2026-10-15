import test from 'node:test';
import assert from 'node:assert/strict';
import { busFreshness, etaText, isRegional, regionLabel, DEFAULT_REGION } from '../src/utils/liveMap.js';

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
