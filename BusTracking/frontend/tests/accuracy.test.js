// Route isolation, position validation, location handling, nearby-stop filtering, ETA sanity and
// journey labelling. Pure modules only, so it runs under `node --test` with no browser.
import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleBuses, distance } from '../src/utils/navigation.js';
import { isValidCoordinate, inDelhiNcr, toLocation, COARSE_ACCURACY_M } from '../src/utils/geo.js';
import {
  etaStatus, filterStandsByRoute, routeUnconfirmed, ROUTE_CHECK_TEXT, approachingBuses, groupStands,
} from '../src/utils/liveMap.js';
import { journeyDurationBasis } from '../src/utils/journey.js';
import { translate, DICTIONARIES } from '../src/i18n/index.js';

const NOW = 2_000_000;
const bus = (id, route, extra = {}) => ({
  vehicleId: id, routeCode: route, busLatitude: 28.70, busLongitude: 77.10,
  lastUpdatedEpochSeconds: NOW - 5, ...extra,
});

// ── Route isolation (Route 106) ─────────────────────────────────────────────

test('route 106 shows only route 106 buses, never 106A, 1060, 160 or 206', () => {
  const feed = [bus('a', '106'), bus('b', '106A'), bus('c', '1060'), bus('d', '160'), bus('e', '206'), bus('f', '106')];
  assert.deepEqual(visibleBuses(feed, '106', NOW).map(b => b.vehicleId), ['a', 'f']);
  assert.deepEqual(visibleBuses(feed, ' 106 ', NOW).map(b => b.vehicleId), ['a', 'f'], 'whitespace is ignored');
});

test('a bus with no route number never appears under a selected route', () => {
  assert.deepEqual(visibleBuses([bus('x', null), bus('y', undefined), bus('z', '106')], '106', NOW).map(b => b.vehicleId), ['z']);
});

test('map list and nearby stops use the same exact route rule', () => {
  const stands = [
    { id: 's1', routeNumbers: ['106', '106A'], upcoming: [{ vehicleId: 'a', routeNumber: '106' }, { vehicleId: 'b', routeNumber: '106A' }] },
    { id: 's2', routeNumbers: ['106A'], upcoming: [{ vehicleId: 'c', routeNumber: '106A' }] },
    { id: 's3', routeNumbers: ['990'], upcoming: [] },
  ];
  const filtered = filterStandsByRoute(stands, '106');
  assert.deepEqual(filtered.map(s => s.id), ['s1']);
  assert.deepEqual(filtered[0].upcoming.map(u => u.vehicleId), ['a']);
  assert.equal(filtered[0].liveCount, 1);
  // Same buses the map would show for 106.
  const mapIds = visibleBuses([bus('a', '106'), bus('b', '106A'), bus('c', '106A')], '106', NOW).map(b => b.vehicleId);
  assert.deepEqual(mapIds, filtered.flatMap(s => s.upcoming.map(u => u.vehicleId)));
});

test('route filter: no route returns every stand; empty or missing data is safe', () => {
  const stands = [{ id: 's1', routeNumbers: ['106'], upcoming: [] }];
  assert.equal(filterStandsByRoute(stands, ''), stands);
  assert.deepEqual(filterStandsByRoute([], '106'), []);
  assert.deepEqual(filterStandsByRoute(undefined, '106'), []);
  assert.deepEqual(filterStandsByRoute([{ id: 'x' }], '106'), [], 'a stop with no known routes is not listed for a route');
  // A stand that serves the route stays even with no tracked bus: it is still the place to wait.
  assert.equal(filterStandsByRoute([{ id: 'q', routeNumbers: ['106'] }], '106')[0].liveCount, 0);
});

// ── Position validation and duplicates ──────────────────────────────────────

test('malformed or out-of-range coordinates are rejected', () => {
  const bad = [
    bus('nan', '106', { busLatitude: NaN }),
    bus('inf', '106', { busLongitude: -Infinity }),
    bus('str', '106', { busLatitude: '28.7' }),
    bus('lat', '106', { busLatitude: 95 }),
    bus('lng', '106', { busLongitude: 200 }),
    bus('zero', '106', { busLatitude: 0, busLongitude: 0 }),
    bus('mumbai', '106', { busLatitude: 19.07, busLongitude: 72.87 }),
    bus('missing', '106', { busLatitude: undefined }),
  ];
  assert.deepEqual(visibleBuses([...bad, bus('ok', '106')], '106', NOW).map(b => b.vehicleId), ['ok']);
});

test('duplicate vehicle records give one marker at the newest GPS fix', () => {
  const older = bus('v1', '106', { lastUpdatedEpochSeconds: NOW - 30, busLatitude: 28.71 });
  const newer = bus('v1', '106', { lastUpdatedEpochSeconds: NOW - 10, busLatitude: 28.72 });
  for (const rows of [[older, newer], [newer, older]]) {
    const out = visibleBuses(rows, '106', NOW);
    assert.equal(out.length, 1);
    assert.equal(out[0].busLatitude, 28.72, 'an out-of-order older record must not move the marker back');
  }
});

test('stale GPS is never shown as a live position; empty and missing responses give no buses', () => {
  assert.deepEqual(visibleBuses([bus('old', '106', { lastUpdatedEpochSeconds: NOW - 121 })], '106', NOW), []);
  assert.deepEqual(visibleBuses([], '106', NOW), []);
  assert.deepEqual(visibleBuses(undefined, '106', NOW), []);
  assert.deepEqual(visibleBuses([null, bus('ok', '106')], '106', NOW).map(b => b.vehicleId), ['ok']);
});

test('coordinate helpers', () => {
  assert.ok(isValidCoordinate(28.6, 77.2));
  assert.ok(!isValidCoordinate(0, 0));
  assert.ok(!isValidCoordinate(-91, 0));
  assert.ok(inDelhiNcr(28.6, 77.2));
  assert.ok(!inDelhiNcr(12.97, 77.59), 'Bengaluru is not in Delhi NCR');
});

// ── Route check (backend routeCheck field) ──────────────────────────────────

test('only OFF_ROUTE marks a route number unconfirmed; a missing field is not treated as confirmed text', () => {
  assert.ok(routeUnconfirmed({ routeCheck: 'OFF_ROUTE' }));
  for (const c of ['TRIP_MATCH', 'ON_ROUTE', 'NO_STOPS', undefined]) assert.ok(!routeUnconfirmed({ routeCheck: c }));
  assert.equal(ROUTE_CHECK_TEXT[undefined], undefined, 'older servers show "not checked", not a confirmation');
});

// ── User location ───────────────────────────────────────────────────────────

const fix = (latitude, longitude, accuracy = 20, timestamp = 1) => ({ coords: { latitude, longitude, accuracy }, timestamp });

test('a valid browser fix becomes a location; precise fixes are not coarse', () => {
  assert.deepEqual(toLocation(fix(28.6, 77.2, 12, 99)), { lat: 28.6, lng: 77.2, accuracy: 12, timestamp: 99, coarse: false });
});

test('invalid browser readings are rejected instead of shown', () => {
  for (const p of [fix(NaN, 77.2), fix(28.6, Infinity), fix(0, 0), fix(120, 77), null, {}, { coords: {} }]) {
    assert.equal(toLocation(p), null);
  }
});

test('inaccurate or unreported accuracy is flagged as approximate', () => {
  assert.equal(toLocation(fix(28.6, 77.2, COARSE_ACCURACY_M + 1)).coarse, true);
  assert.equal(toLocation(fix(28.6, 77.2, NaN)).accuracy, null);
  assert.equal(toLocation(fix(28.6, 77.2, -5)).coarse, true);
});

/**
 * Loads a fresh copy of the shared GPS store with a fake navigator.geolocation. `respond(success,
 * failure)` decides what getCurrentPosition does.
 */
async function gpsWith(respond, tag) {
  const watches = [];
  const geolocation = {
    getCurrentPosition: (ok, fail) => respond(ok, fail),
    watchPosition: () => { watches.push(1); return watches.length; },
    clearWatch: () => {},
  };
  Object.defineProperty(globalThis, 'navigator', { value: { geolocation }, configurable: true, writable: true });
  const mod = await import(`../src/hooks/useUserLocation.js?case=${tag}`);
  return { mod, watches };
}

test('location permission granted: locate() resolves the fix', async () => {
  const { mod } = await gpsWith(ok => ok(fix(28.61, 77.21, 15)), 'granted');
  const loc = await mod.locate();
  assert.equal(loc.lat, 28.61);
  assert.equal(loc.lng, 77.21);
});

test('location permission denied, unavailable or timed out: no position and a clear reason', async () => {
  for (const [code, tag, status, words] of [
    [1, 'denied', 'denied', /blocked/],
    [2, 'unavailable', 'error', /could not determine/],
    [3, 'timeout', 'error', /too long/],
  ]) {
    const { mod, watches } = await gpsWith((ok, fail) => fail({ code }), tag);
    assert.equal(await mod.locate(), null, `code ${code} gives no location`);
    const s = mod.getLocationState();
    assert.equal(s.status, status);
    assert.equal(s.location, null);
    assert.match(s.error, words);
    assert.equal(watches.length, 0, 'no location watch is started after a failure');
  }
});

test('a later failure keeps the last good position rather than dropping it', async () => {
  let calls = 0;
  const { mod } = await gpsWith((ok, fail) => (calls++ === 0 ? ok(fix(28.6, 77.2)) : fail({ code: 3 })), 'keep');
  await mod.locate();
  assert.equal(await mod.locate(), null);
  const s = mod.getLocationState();
  assert.equal(s.status, 'ready');
  assert.equal(s.location.lat, 28.6, 'the earlier fix is still shown, with its own timestamp');
  assert.match(s.error, /too long/);
});

test('an invalid reading from the device is not used as the user location', async () => {
  const { mod } = await gpsWith(ok => ok(fix(NaN, NaN)), 'invalid');
  assert.equal(await mod.locate(), null);
  assert.equal(mod.getLocationState().location, null);
  assert.match(mod.getLocationState().error, /not valid/);
});

test('unsupported browser: locate() resolves null without throwing', async () => {
  Object.defineProperty(globalThis, 'navigator', { value: {}, configurable: true, writable: true });
  const mod = await import('../src/hooks/useUserLocation.js?case=unsupported');
  assert.equal(await mod.locate(), null);
  assert.equal(mod.getLocationState().status, 'unsupported');
});

// ── Nearest stops ───────────────────────────────────────────────────────────

test('distance: haversine agrees with a known separation', () => {
  // 0.01° of latitude is about 1.11 km anywhere.
  assert.ok(Math.abs(distance({ lat: 28.6, lng: 77.2 }, { lat: 28.61, lng: 77.2 }) - 1112) < 5);
  assert.equal(distance({ lat: 28.6, lng: 77.2 }, { lat: 28.6, lng: 77.2 }), 0);
});

test('stands keep nearest-first order and merge only same-name stops close together', () => {
  const stops = [
    { id: 'a', name: 'Rohini Sec 7', lat: 28.7000, lng: 77.1000, distanceMeters: 50, routeNumbers: ['106'], upcoming: [] },
    { id: 'b', name: 'Rohini Sec 7', lat: 28.7005, lng: 77.1000, distanceMeters: 90, routeNumbers: ['990'], upcoming: [] },
    { id: 'c', name: 'Other', lat: 28.7010, lng: 77.1000, distanceMeters: 120, routeNumbers: [], upcoming: [] },
  ];
  const stands = groupStands(stops);
  assert.deepEqual(stands.map(s => s.id), ['a', 'c']);
  assert.deepEqual(stands[0].routeNumbers, ['106', '990']);
});

test('approaching buses ignore entries without a valid live position', () => {
  const stops = [{ id: 's', name: 'S', upcoming: [
    { vehicleId: 'ok', busLatitude: 28.7, busLongitude: 77.1, etaMinutes: 4 },
    { vehicleId: 'noPos', busLatitude: null, busLongitude: null, etaMinutes: 1 },
  ] }];
  assert.deepEqual(approachingBuses(stops).map(b => b.vehicleId), ['ok']);
});

// ── ETA sanity and staleness ────────────────────────────────────────────────

test('ETA status: valid backend ETA is shown', () => {
  assert.equal(etaStatus({ etaMinutes: 5, availability: 'TRIP_UPDATE', lastGpsUpdate: NOW - 20 }, NOW), 'ok');
  assert.equal(etaStatus({ etaMinutes: 0.2, lastGpsUpdate: NOW - 20 }, NOW), 'ok');
});

test('ETA status: missing, invalid and out-of-date ETAs never show a number', () => {
  assert.equal(etaStatus({ etaMinutes: null, availability: 'UNAVAILABLE' }, NOW), 'none');
  assert.equal(etaStatus(undefined, NOW), 'none');
  assert.equal(etaStatus({ etaMinutes: NaN }, NOW), 'none');
  assert.equal(etaStatus({ etaMinutes: -10 }, NOW), 'invalid');
  assert.equal(etaStatus({ etaMinutes: 600 }, NOW), 'invalid');
  // Refreshes stopped: the GPS behind this ETA is now over 2 minutes old.
  assert.equal(etaStatus({ etaMinutes: 3, lastGpsUpdate: NOW - 121 }, NOW), 'stale');
});

// ── Journey labelling ───────────────────────────────────────────────────────

test('journey duration basis: timetable, approximate and unavailable are distinguished', () => {
  const leg = basis => ({ type: 'BUS', basis });
  const walk = { type: 'WALK', basis: 'STRAIGHT_LINE' };
  assert.equal(journeyDurationBasis({ totalMinutes: 40, segments: [walk, leg('SCHEDULED_GTFS'), walk] }), 'SCHEDULED');
  assert.equal(journeyDurationBasis({ totalMinutes: 40, segments: [walk, leg('SCHEDULED_GTFS'), leg('ASSUMED_SPEED')] }), 'APPROXIMATE');
  assert.equal(journeyDurationBasis({ totalMinutes: null, segments: [leg('SCHEDULED_GTFS')] }), 'UNAVAILABLE');
  assert.equal(journeyDurationBasis({ totalMinutes: 10, segments: [walk] }), 'UNAVAILABLE', 'walking only: no bus journey');
});

// ── Translation ─────────────────────────────────────────────────────────────

test('translation: Hindi where translated, English fallback, placeholders filled', () => {
  assert.equal(translate('hi', 'nav.nearby'), DICTIONARIES.hi['nav.nearby']);
  assert.equal(translate('hi', 'no.such.key'), 'no.such.key');
  assert.equal(translate('xx', 'nav.plan'), 'Plan');
  assert.equal(translate('en', 'map.fitRoute', { route: '106' }), 'Fit map to route 106');
  assert.match(translate('hi', 'map.fitRoute', { route: '106' }), /106/, 'route numbers pass through untranslated');
  for (const key of Object.keys(DICTIONARIES.en)) assert.ok(DICTIONARIES.hi[key], `Hindi has ${key}`);
});
