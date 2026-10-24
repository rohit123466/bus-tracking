import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyError, isAbort } from '../src/api/errors.js';
import {
  getConnection, reportFailure, reportSuccess, reportBrowserOnline, resetConnection,
} from '../src/api/connection.js';
import { createPersistentStore } from '../src/store/persistent.js';
import { toggleRouteIn, toggleStopIn, addRecentTo, MAX_RECENT } from '../src/store/favourites.js';
import { addWatchTo, dueAnnouncements, watchId, MAX_WATCHES } from '../src/store/watches.js';
import { ageText, durationText, distanceText } from '../src/utils/format.js';
import { feedState, sortByEta, FEED_STALE_AFTER_MS } from '../src/utils/liveMap.js';
import { journeyPoints, parsePlaceParam, placeParam, busSegments } from '../src/utils/journey.js';

// ── API errors ──────────────────────────────────────────────────────────────
test('errors: network, timeout, gateway, 404 and aborts are told apart', () => {
  assert.equal(classifyError({ code: 'ERR_NETWORK' }).kind, 'unreachable');
  assert.equal(classifyError({ code: 'ECONNABORTED' }).kind, 'timeout');
  assert.equal(classifyError({ response: { status: 503 } }).kind, 'starting');
  assert.equal(classifyError({ response: { status: 404, data: { message: 'Unknown GTFS stop ID' } } }).message, 'Unknown GTFS stop ID');
  assert.equal(classifyError({ response: { status: 500 } }).retryable, true);
  assert.equal(classifyError({ response: { status: 400 } }).retryable, false);
  assert.equal(classifyError({ code: 'ERR_NETWORK' }, false).kind, 'offline');
  assert.equal(isAbort({ name: 'CanceledError' }), true);
  assert.equal(classifyError({ code: 'ERR_CANCELED' }).kind, 'aborted');
});

// ── Connection state ───────────────────────────────────────────────────────
test('connection: never answered = waking; answered before = reconnecting; offline wins', () => {
  resetConnection();
  assert.equal(getConnection().state, 'connecting');
  reportFailure('timeout');
  assert.equal(getConnection().state, 'waking');
  reportSuccess(1000);
  assert.equal(getConnection().state, 'online');
  assert.equal(getConnection().lastOkAt, 1000);
  reportFailure('not-found'); // the server answered: not a connectivity problem
  assert.equal(getConnection().state, 'online');
  reportFailure('unreachable');
  assert.equal(getConnection().state, 'reconnecting');
  reportBrowserOnline(false);
  assert.equal(getConnection().state, 'offline');
  reportBrowserOnline(true);
  assert.equal(getConnection().state, 'reconnecting');
  reportSuccess();
  assert.equal(getConnection().state, 'online');
});

// ── Stores ─────────────────────────────────────────────────────────────────
test('persistent store works without localStorage and notifies subscribers', () => {
  const store = createPersistentStore('test_key', [], Array.isArray);
  let calls = 0;
  const off = store.subscribe(() => calls++);
  store.set(list => [...list, 1]);
  assert.deepEqual(store.get(), [1]);
  assert.equal(calls, 1);
  off();
  store.set([]);
  assert.equal(calls, 1);
});

test('favourites: toggling adds then removes; recent searches dedupe and cap', () => {
  const routes = toggleRouteIn([], '534');
  assert.deepEqual(routes, [{ routeCode: '534' }]);
  assert.deepEqual(toggleRouteIn(routes, '534'), []);
  const stops = toggleStopIn([], { id: 's1', name: 'A', lat: 1, lng: 2, extra: 'x' });
  assert.deepEqual(stops, [{ id: 's1', name: 'A', lat: 1, lng: 2 }]);
  let recent = addRecentTo([], '534', 'route', 1);
  recent = addRecentTo(recent, '990', 'route', 2);
  recent = addRecentTo(recent, '534', 'route', 3);
  assert.deepEqual(recent.map(r => r.query), ['534', '990']);
  const unchanged = [];
  assert.equal(addRecentTo(unchanged, '  ', 'route'), unchanged);
  for (let i = 0; i < 20; i++) recent = addRecentTo(recent, `r${i}`, 'route');
  assert.equal(recent.length, MAX_RECENT);
});

// ── Arrival alerts ─────────────────────────────────────────────────────────
const stop = { id: 's1', name: 'Azadpur', lat: 28.7, lng: 77.17 };

test('alerts: one watch per stop+route, capped', () => {
  let list = addWatchTo([], { stop, routeNumber: '534', thresholdMinutes: 5 });
  list = addWatchTo(list, { stop, routeNumber: '534', thresholdMinutes: 3 });
  assert.equal(list.length, 1);
  assert.equal(list[0].thresholdMinutes, 3);
  assert.equal(list[0].id, watchId('s1', '534'));
  for (let i = 0; i < 10; i++) list = addWatchTo(list, { stop: { ...stop, id: `x${i}` } });
  assert.equal(list.length, MAX_WATCHES);
});

test('alerts: announce only fresh, estimated buses within the threshold, once each', () => {
  const now = 10_000;
  const watch = addWatchTo([], { stop, routeNumber: '534', thresholdMinutes: 5 })[0];
  const upcoming = [
    { routeNumber: '534', vehicleId: 'a', etaMinutes: 4.2, availability: 'LEARNED_ROUTE_ESTIMATE', lastGpsUpdate: now - 20 },
    { routeNumber: '534', vehicleId: 'b', etaMinutes: 9, availability: 'LEARNED_ROUTE_ESTIMATE', lastGpsUpdate: now - 20 },
    { routeNumber: '534', vehicleId: 'c', etaMinutes: null, availability: 'UNAVAILABLE', lastGpsUpdate: now - 20 },
    { routeNumber: '534', vehicleId: 'd', etaMinutes: 2, availability: 'STOP_CHAIN_ESTIMATE', lastGpsUpdate: now - 600 },
    { routeNumber: '990', vehicleId: 'e', etaMinutes: 1, availability: 'TRIP_UPDATE', lastGpsUpdate: now - 5 },
  ];
  const due = dueAnnouncements(watch, upcoming, new Map(), now);
  assert.deepEqual(due.map(d => d.key), [`${watch.id}|a`]);
  assert.match(due[0].title, /about 4 min/);
  assert.match(due[0].body, /estimated/);
  const announced = new Map([[due[0].key, Date.now()]]);
  assert.equal(dueAnnouncements(watch, upcoming, announced, now).length, 0);
  // "Any bus" watches include other routes; TripUpdates times are not called estimates.
  const any = addWatchTo([], { stop, thresholdMinutes: 5 })[0];
  const exact = dueAnnouncements(any, upcoming, new Map(), now).find(d => d.key.endsWith('|e'));
  assert.match(exact.title, /Route 990 1 min away/);
  assert.doesNotMatch(exact.body, /estimated/);
});

// ── Formatting & live state ────────────────────────────────────────────────
test('format: ages, durations and distances', () => {
  assert.equal(ageText(995, 1000), 'just now');
  assert.equal(ageText(940, 1000), '1 min ago');
  assert.equal(ageText(970, 1000), '30 s ago');
  assert.equal(ageText(null, 1000), 'unknown');
  assert.equal(durationText(54.4), '54 min');
  assert.equal(durationText(72), '1 h 12 min');
  assert.equal(durationText(0.2), 'under 1 min');
  assert.equal(durationText(0.7), '1 min');
  assert.equal(distanceText(850.4), '850 m');
  assert.equal(distanceText(12400), '12.4 km');
});

test('feed state: loading, live, stale after the threshold, down when the feed says so', () => {
  const t = 1_000_000;
  assert.equal(feedState({}, t), 'loading');
  assert.equal(feedState({ error: { kind: 'timeout' } }, t), 'error');
  assert.equal(feedState({ updatedAt: t - 5000, data: { feedAvailable: true } }, t), 'live');
  assert.equal(feedState({ updatedAt: t - FEED_STALE_AFTER_MS - 1, data: {} }, t), 'stale');
  assert.equal(feedState({ updatedAt: t, data: { feedAvailable: false } }, t), 'down');
});

test('sortByEta puts estimates first and buses without a time last', () => {
  const sorted = sortByEta([
    { routeNumber: '10', etaMinutes: null }, { routeNumber: '9', etaMinutes: 7 }, { routeNumber: '2', etaMinutes: 3 },
  ]);
  assert.deepEqual(sorted.map(s => s.routeNumber), ['2', '9', '10']);
});

// ── Journey helpers ────────────────────────────────────────────────────────
test('journey: place params round-trip and reject points outside Delhi NCR', () => {
  const p = { lat: 28.7041, lng: 77.1025, label: 'Pitampura' };
  assert.deepEqual(parsePlaceParam(placeParam(p)), { ...p, kind: 'place' });
  assert.equal(parsePlaceParam('51.5,-0.12|London'), null);
  assert.equal(parsePlaceParam('nonsense'), null);
});

test('journey: map points follow bus stops in order, walking ends included', () => {
  const j = {
    segments: [
      { type: 'WALK', from: { lat: 1, lng: 1 }, to: { lat: 2, lng: 2 } },
      { type: 'BUS', routeNumber: '534', from: { lat: 2, lng: 2 }, to: { lat: 4, lng: 4 }, stops: [{ lat: 2, lng: 2 }, { lat: 3, lng: 3 }, { lat: 4, lng: 4 }] },
      { type: 'WALK', from: { lat: 4, lng: 4 }, to: { lat: 5, lng: 5 } },
    ],
  };
  assert.deepEqual(journeyPoints(j).map(p => p[0]), [1, 2, 2, 3, 4, 4, 5]);
  assert.deepEqual(busSegments(j).map(s => s.routeNumber), ['534']);
});
