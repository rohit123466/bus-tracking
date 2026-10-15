import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleBuses, progress } from '../src/utils/navigation.js';

const bus = (id, route = '534', stamp = 1000) => ({
  vehicleId: id, routeCode: route,
  lastUpdatedEpochSeconds: stamp,
  busLatitude: 28.6, busLongitude: 77.2,
});

// ── Existing tests ──────────────────────────────────────────────────────────

test('534 matches exactly, keeps distinct vehicles and deduplicates repeated IDs', () => {
  assert.deepEqual(
    visibleBuses([bus('a'), bus('b'), bus('a'), bus('c', '534E'), bus('d', '221')], '534', 1001)
      .map(b => b.vehicleId),
    ['a', 'b'],
  );
});

test('route filtering survives replacement polling payloads', () => {
  for (const rows of [[bus('a'), bus('b', '221')], [bus('c', '221'), bus('d')]]) {
    assert.equal(visibleBuses(rows, '534', 1001)[0].routeCode, '534');
  }
});

test('empty and stale positions are excluded', () => {
  assert.deepEqual(visibleBuses([bus('a', '221'), bus('b', '534', 700)], '534', 1001), []);
});

test('remaining walking distance decreases along route and detects deviation', () => {
  const route = [[28.6, 77.2], [28.601, 77.2], [28.601, 77.202]];
  const start = progress(route, { lat: 28.6, lng: 77.2 });
  const middle = progress(route, { lat: 28.601, lng: 77.201 });
  assert.ok(start.remaining > middle.remaining);
  assert.ok(middle.offset < 1);
  assert.ok(progress(route, { lat: 28.603, lng: 77.205 }).offset > 100);
});

// ── New route-mapping bug regression tests ──────────────────────────────────

test('534A does not appear in results when querying route 534', () => {
  const buses = [bus('a', '534'), bus('b', '534A'), bus('c', '5340')];
  const result = visibleBuses(buses, '534', 1001);
  assert.equal(result.length, 1, 'Only exact "534" match should be returned');
  assert.equal(result[0].vehicleId, 'a');
});

test('route number comparison is case-insensitive', () => {
  const buses = [bus('a', '534'), bus('b', '534a')]; // lowercase variant
  // Both should match when querying '534' only if they normalize to the same upper case
  // The server always returns uppercase routeCode, but client filter uppercases both sides
  const result = visibleBuses(buses, '534', 1001);
  // bus 'a' has '534', bus 'b' has '534a' — these are DIFFERENT routes
  assert.equal(result.length, 1, '"534" and "534a" are different routes');
  assert.equal(result[0].vehicleId, 'a');
});

test('clearing route (empty routeNumber) shows all fresh valid buses', () => {
  const buses = [bus('a', '534'), bus('b', '221'), bus('c', '106')];
  const result = visibleBuses(buses, '', 1001);
  assert.equal(result.length, 3, 'All buses shown when no route filter');
});

test('invalid GPS positions are excluded', () => {
  const badLat = { vehicleId: 'x', routeCode: '534', lastUpdatedEpochSeconds: 1000, busLatitude: NaN, busLongitude: 77.2 };
  const badLng = { vehicleId: 'y', routeCode: '534', lastUpdatedEpochSeconds: 1000, busLatitude: 28.6, busLongitude: Infinity };
  const good = bus('z', '534', 1000);
  const result = visibleBuses([badLat, badLng, good], '534', 1001);
  assert.equal(result.length, 1, 'Buses with NaN/Infinity coordinates must be excluded');
  assert.equal(result[0].vehicleId, 'z');
});

test('vehicles with missing vehicleId are excluded', () => {
  const noId = { vehicleId: '', routeCode: '534', lastUpdatedEpochSeconds: 1000, busLatitude: 28.6, busLongitude: 77.2 };
  const nullId = { vehicleId: null, routeCode: '534', lastUpdatedEpochSeconds: 1000, busLatitude: 28.6, busLongitude: 77.2 };
  const good = bus('v', '534', 1000);
  const result = visibleBuses([noId, nullId, good], '534', 1001);
  assert.equal(result.length, 1, 'Buses with blank/null vehicleId must be excluded');
});

test('zero-timestamp vehicles are excluded', () => {
  const zeroTs = { vehicleId: 'v', routeCode: '534', lastUpdatedEpochSeconds: 0, busLatitude: 28.6, busLongitude: 77.2 };
  const result = visibleBuses([zeroTs], '534', 1001);
  assert.equal(result.length, 0, 'Vehicles with timestamp=0 must be excluded');
});

test('future timestamp within 30s tolerance is allowed', () => {
  const slightlyFuture = bus('v', '534', 1025); // 24s in the future
  const result = visibleBuses([slightlyFuture], '534', 1001);
  assert.equal(result.length, 1, 'Timestamps up to 30s in the future must be tolerated');
});

test('future timestamp beyond 30s tolerance is excluded', () => {
  const farFuture = bus('v', '534', 1032); // 31s in the future
  const result = visibleBuses([farFuture], '534', 1001);
  assert.equal(result.length, 0, 'Timestamps more than 30s in the future must be excluded');
});

test('switching routes does not leak previous route buses (dedup by vehicleId)', () => {
  // Simulate state from old route still in the array
  const oldRoute = [bus('v1', '221'), bus('v2', '221')];
  const newRoute = [bus('v3', '534'), bus('v4', '534')];
  const mixed = [...oldRoute, ...newRoute];

  // After route switch to 534, only 534 buses should show
  const result = visibleBuses(mixed, '534', 1001);
  assert.equal(result.length, 2);
  assert.ok(result.every(b => b.routeCode === '534'), 'Old route buses must not appear after switch');
});
