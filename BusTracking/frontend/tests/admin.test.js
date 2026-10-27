import test from 'node:test';
import assert from 'node:assert/strict';
import {
  countBySeverity, countdownText, countStatuses, eventFromFleetRow, liveStatus, loadTone, loginErrorMessage,
  mergeHistory, mergePositions, pushSample, sampleFromStats, searchItems, sortBy, sparklinePath, toCsv, tokenExpiry,
  trend, visibleBuses, STALE_AFTER_SECONDS, SLOW_SPEED_KMH,
} from '../src/utils/admin.js';

const NOW = 1_800_000_000;

// ── Live status: must match BusLiveStatus.java ───────────────────────────────
test('admin status: thresholds match the backend (120 s stale, under 5 km/h slow)', () => {
  assert.equal(STALE_AFTER_SECONDS, 120);
  assert.equal(SLOW_SPEED_KMH, 5);
  assert.equal(liveStatus(null, NOW), 'NO_DATA');
  assert.equal(liveStatus({ timestamp: NOW - 120, speedKmh: 30 }, NOW), 'MOVING');
  assert.equal(liveStatus({ timestamp: NOW - 121, speedKmh: 30 }, NOW), 'STALE');
  assert.equal(liveStatus({ timestamp: null, speedKmh: 30 }, NOW), 'STALE');
  assert.equal(liveStatus({ timestamp: NOW - 10, speedKmh: 4.9 }, NOW), 'SLOW');
  assert.equal(liveStatus({ timestamp: NOW - 10, speedKmh: 5 }, NOW), 'MOVING');
  assert.equal(liveStatus({ timestamp: NOW - 10, speedKmh: null }, NOW), 'MOVING');
  assert.equal(liveStatus({ timestamp: NOW - 500, speedKmh: 0 }, NOW), 'STALE');
});

test('admin status: map filtering and counts by route and toggles', () => {
  const positions = {
    A: { vehicleId: 'A', routeId: 'r1', latitude: 28.7, longitude: 77.1, timestamp: NOW - 5, speedKmh: 30 },
    B: { vehicleId: 'B', routeId: 'r1', latitude: 28.7, longitude: 77.1, timestamp: NOW - 5, speedKmh: 1 },
    C: { vehicleId: 'C', routeId: 'r2', latitude: 28.7, longitude: 77.1, timestamp: NOW - 400, speedKmh: 20 },
    D: { vehicleId: 'D', routeId: 'r2', latitude: null, longitude: null, timestamp: NOW - 5, speedKmh: 20 },
  };
  assert.deepEqual(countStatuses(positions, { now: NOW }), { MOVING: 1, SLOW: 1, STALE: 1 });
  assert.deepEqual(countStatuses(positions, { route: 'r1', now: NOW }), { MOVING: 1, SLOW: 1, STALE: 0 });
  const shown = visibleBuses(positions, { shown: new Set(['SLOW', 'STALE']), now: NOW });
  assert.deepEqual(shown.map(b => [b.vehicleId, b.status]), [['B', 'SLOW'], ['C', 'STALE']]);
});

test('admin positions: socket pushes win unless older than the polled fix', () => {
  const polled = { A: { vehicleId: 'A', timestamp: 100 }, B: { vehicleId: 'B', timestamp: 200 } };
  const pushed = { A: { vehicleId: 'A', timestamp: 150 }, B: { vehicleId: 'B', timestamp: 190 }, C: { vehicleId: 'C', timestamp: 1 } };
  const merged = mergePositions(polled, pushed);
  assert.equal(merged.A.timestamp, 150);
  assert.equal(merged.B.timestamp, 200);
  assert.ok(merged.C);
  const e = eventFromFleetRow({ vehicleId: 'V', routeCode: 'r', latitude: 1, longitude: 2, speedKmh: 3, bearing: 4, lastSeen: 5, wheelchairSpaceAvailable: null });
  assert.deepEqual(e, { vehicleId: 'V', routeId: 'r', latitude: 1, longitude: 2, speedKmh: 3, bearing: 4, timestamp: 5, wheelchairSpaceAvailable: null });
});

// ── Sparkline history ────────────────────────────────────────────────────────
test('admin history: ring buffer keeps the newest samples, skips repeats', () => {
  let buf = [];
  for (let i = 1; i <= 35; i++) buf = pushSample(buf, { at: i, live: i }, 30);
  assert.equal(buf.length, 30);
  assert.equal(buf[0].at, 6);
  assert.equal(pushSample(buf, { at: 35, live: 0 }, 30), buf, 'same timestamp is not added twice');
  const s = sampleFromStats({ liveBusCount: 4, offlineBusCount: 6, delayedBusCount: 1, averageSpeedKmh: 17.5 }, 99);
  assert.deepEqual(s, { at: 99, live: 4, offline: 6, slow: 1, averageSpeedKmh: 17.5 });
});

test('admin history: server samples and client polls merge in time order', () => {
  const merged = mergeHistory([{ at: 10 }, { at: 40 }], [{ at: 20 }, { at: 40 }, { at: 50 }], 3);
  assert.deepEqual(merged.map(s => s.at), [20, 40, 50]);
  assert.deepEqual(mergeHistory(undefined, [{ at: 1 }]).map(s => s.at), [1]);
});

test('admin history: trend and sparkline path', () => {
  const samples = [{ at: 0, live: 10 }, { at: 60000, live: 15 }, { at: 120000, live: 12 }];
  assert.deepEqual(trend(samples, 'live'), { delta: 2, sinceMs: 120000 });
  assert.equal(trend(samples.slice(0, 1), 'live'), null);
  const d = sparklinePath(samples, 'live', 100, 20, 0);
  assert.equal(d, 'M0.0 20.0 L50.0 0.0 L100.0 12.0');
  assert.equal(sparklinePath([{ at: 0, live: 3 }, { at: 1, live: 3 }], 'live', 10, 10, 0), 'M0.0 5.0 L10.0 5.0', 'flat line in the middle');
  assert.equal(sparklinePath(samples.slice(0, 1), 'live', 10, 10), '');
});

// ── Session ──────────────────────────────────────────────────────────────────
test('admin session: exp decoded from the JWT, countdown text', () => {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const token = `${b64({ alg: 'HS256' })}.${b64({ sub: 'admin', exp: 1_900_000_000 })}.sig`;
  assert.equal(tokenExpiry(token), 1_900_000_000_000);
  assert.equal(tokenExpiry('not-a-jwt'), null);
  assert.equal(tokenExpiry(`${b64({})}.${b64({ sub: 'x' })}.s`), null);
  assert.equal(countdownText(4 * 60000 + 5000), '4:05');
  assert.equal(countdownText(25 * 3600000 + 60000), '25 h 1 min');
  assert.equal(countdownText(-5), '0:00');
});

test('admin login: lockout, bad password and network failures read differently', () => {
  assert.match(loginErrorMessage({ response: { status: 429, data: { message: 'x' } } }), /locked for up to 15 minutes/);
  assert.equal(loginErrorMessage({ response: { status: 401 } }), 'Wrong username or password.');
  assert.match(loginErrorMessage({ code: 'ERR_NETWORK' }), /Can't reach the server/);
  assert.equal(loginErrorMessage({ response: { status: 400, data: { message: 'username: must not be blank' } } }), 'username: must not be blank');
});

// ── Tables, CSV, misc ────────────────────────────────────────────────────────
test('admin tables: sort with missing values last, natural string order', () => {
  const rows = [{ k: '10' }, { k: null }, { k: '9' }, { k: '534A' }];
  assert.deepEqual(sortBy(rows, 'k').map(r => r.k), ['9', '10', '534A', null]);
  assert.deepEqual(sortBy(rows, 'k', 'desc').map(r => r.k), ['534A', '10', '9', null]);
  assert.deepEqual(sortBy([{ n: 2 }, { n: 10 }, { n: undefined }], 'n', 'desc').map(r => r.n), [10, 2, undefined]);
});

test('admin csv: quoting, empty cells and formula injection', () => {
  const csv = toCsv(
    [{ a: 'plain', b: 'has, comma' }, { a: '=HYPERLINK("x")', b: null }, { a: '-12.5', b: 'say "hi"' }],
    [{ key: 'a', label: 'A' }, { key: 'b', label: 'B', value: (r) => r.b }],
  );
  assert.equal(csv, 'A,B\r\nplain,"has, comma"\r\n"\'=HYPERLINK(""x"")",\r\n-12.5,"say ""hi"""\r\n');
});

test('admin misc: occupancy tone, severity counts, palette search', () => {
  assert.deepEqual([0, 59.9, 60, 84, 85, 120].map(loadTone), ['low', 'low', 'mid', 'mid', 'high', 'high']);
  assert.deepEqual(countBySeverity([{ severity: 'CRITICAL' }, { severity: 'INFO' }, { severity: 'INFO' }, { severity: 'ODD' }]),
    { CRITICAL: 1, WARNING: 0, INFO: 2 });
  const items = [{ label: 'Live map' }, { label: 'Route 534', keywords: '1052' }, { label: 'DL1PC0534' }, { label: 'Overview' }];
  assert.deepEqual(searchItems(items, '534').map(i => i.label), ['Route 534', 'DL1PC0534']);
  assert.deepEqual(searchItems(items, 'dl1').map(i => i.label), ['DL1PC0534']);
  assert.deepEqual(searchItems(items, '1052').map(i => i.label), ['Route 534']);
  assert.equal(searchItems(items, '', 2).length, 2);
});
