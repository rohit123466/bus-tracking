// Browser regression test for the passenger UI. API responses are test fixtures served through
// Playwright request interception (nothing here ships with the app). Starts its own Vite server.
//   npm run test:browser            (uses installed Microsoft Edge; PW_CHANNEL=chromium to change)
import { chromium } from '@playwright/test';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';

const PORT = 5182;
const BASE = `http://127.0.0.1:${PORT}`;
const server = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', String(PORT), '--strictPort'], { stdio: 'ignore' });
const now = () => Math.floor(Date.now() / 1000);

const STOP = { id: 's1', name: 'Azadpur Terminal', lat: 28.7075, lng: 77.18 };
const bus = (id, routeCode, ageSeconds, extra = {}) => ({
  vehicleId: id, routeId: `r${routeCode}`, routeCode, direction: 'UP', tripId: '', busLatitude: 28.70 + Math.random() / 100,
  busLongitude: 77.17, speedKmh: 20, lastUpdatedEpochSeconds: now() - ageSeconds, mappingSource: 'INFERRED',
  distanceFromShapeMeters: null, offRouteFlag: false, regions: ['NORTH'], nextStopId: 's1', nextStopName: STOP.name,
  nextStopSource: 'INFERRED_FROM_GPS_ON_ROUTE', nextStopDistanceMeters: 400, nextStopEtaMinutes: 1.5,
  nextStopEtaBasis: 'STOP_CHAIN_ESTIMATE', ...extra,
});
const upcoming = (routeNumber, vehicleId, etaMinutes, availability) => ({
  routeNumber, vehicleId, etaMinutes, availability, label: '', reason: etaMinutes == null ? 'Route geometry unavailable.' : null,
  remainingStops: 3, lastGpsUpdate: now() - 10, busLatitude: 28.70, busLongitude: 77.17, speedKmh: 18, distanceMeters: 900,
});

function fixtures({ patterns404 = false, journey404 = false } = {}) {
  return {
    '/api/transit/buses/regional': () => ({
      buses: [bus('LIVE1', '534', 5), bus('DELAYED1', '534', 70), bus('STALE1', '534', 400)],
      warnings: [], fetchedAt: now(), feedAvailable: true, region: 'ANY', eligibleRouteCount: 2, excludedByRegion: 0, excludedOutsideArea: 0,
    }),
    '/api/transit/routes/search': () => ['534', '534A'],
    '/api/transit/routes/patterns': () => patterns404 ? 404 : [
      { routeId: 'r534', routeNumber: '534', direction: 'UP', towards: 'Mori Gate', stops: [STOP, { id: 's2', name: 'Mori Gate', lat: 28.67, lng: 77.22 }] },
    ],
    '/api/transit/routes/map': () => ({ stops: [STOP], shapes: [] }),
    '/api/transit/alerts': () => ({ alerts: [], feedStatus: 'OK', fetchedAt: now() }),
    '/api/transit/learning/status': () => ({}),
    '/api/transit/stops/search': () => [STOP],
    '/api/transit/stops/nearby-regional': () => ({
      stops: [{ ...STOP, distanceMeters: 120, walkMinutes: 1.4, walkBasis: 'STRAIGHT_LINE_ESTIMATE', routeNumbers: ['534', '990'], liveCount: 3,
        upcoming: [upcoming('534', 'LIVE1', 6.2, 'LEARNED_ROUTE_ESTIMATE'), upcoming('990', 'X9', null, 'UNAVAILABLE'), upcoming('534', 'TU1', 2.1, 'TRIP_UPDATE')] }],
      radiusMeters: 500, feedAvailable: true, region: 'ANY', warnings: [],
    }),
    '/api/transit/journey/plan': () => journey404 ? 404 : {
      status: 'OK', message: null, notes: ['Based on the static DTC GTFS timetable.'],
      options: [{ direct: true, transfers: 0, totalMinutes: 24, walkMinutes: 3, rideMinutes: 21, walkMeters: 250, segments: [
        { type: 'WALK', from: { id: null, name: 'Start', lat: 28.708, lng: 77.181 }, to: STOP, meters: 150, minutes: 2, basis: 'STRAIGHT_LINE' },
        { type: 'BUS', from: STOP, to: { id: 's2', name: 'Mori Gate', lat: 28.67, lng: 77.22 }, meters: 5000, minutes: 21, basis: 'SCHEDULED_GTFS',
          routeNumber: '534', routeId: 'r534', direction: 'UP', towards: 'Mori Gate', stopCount: 1, stops: [STOP, { id: 's2', name: 'Mori Gate', lat: 28.67, lng: 77.22 }] },
        { type: 'WALK', from: { id: 's2', name: 'Mori Gate', lat: 28.67, lng: 77.22 }, to: { id: null, name: 'Destination', lat: 28.671, lng: 77.221 }, meters: 100, minutes: 1, basis: 'STRAIGHT_LINE' },
      ] }],
    },
  };
}

async function newPage(browser, opts = {}) {
  const ctx = await browser.newContext({
    viewport: opts.viewport ?? { width: 1280, height: 820 },
    geolocation: { latitude: 28.708, longitude: 77.181, accuracy: 15 }, permissions: ['geolocation'],
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const table = fixtures(opts);
  await page.route(u => new URL(u).pathname.startsWith('/api/'), async route => {
    if (opts.down) return route.abort('connectionrefused');
    const handler = table[new URL(route.request().url()).pathname];
    const body = handler ? handler() : 404;
    if (body === 404) return route.fulfill({ status: 404, json: { message: 'Not found' }, headers: { 'access-control-allow-origin': '*' } });
    return route.fulfill({ json: body, headers: { 'access-control-allow-origin': '*' } });
  });
  await page.route(/nominatim|tile\.openstreetmap/, r => r.abort());
  return { page, errors };
}

let browser, passed = 0;
const check = async (name, fn) => { await fn(); passed++; console.log(`✔ ${name}`); };
try {
  for (let i = 0; i < 100; i++) { try { await fetch(BASE); break; } catch { await new Promise(r => setTimeout(r, 150)); } }
  browser = await chromium.launch({ channel: process.env.PW_CHANNEL ?? 'msedge' });

  const { page, errors } = await newPage(browser);
  await check('live map hides positions older than 2 minutes and labels delayed GPS', async () => {
    await page.goto(`${BASE}/?route=534`);
    await page.getByText(/^Live feed · 2 buses on route 534/).waitFor();
    assert.equal(await page.getByText('Delayed GPS').count(), 1);
    assert.equal(await page.getByText('Live GPS', { exact: true }).count() >= 1, true);
  });
  await check('route patterns draw a direction legend', async () => {
    await page.getByText('Towards').first().waitFor();
    assert.match(await page.locator('.side-body').innerText(), /Mori Gate/);
  });
  await check('bus detail opens from the list and Back returns to the route', async () => {
    await page.getByRole('button', { name: /Next: Azadpur Terminal/ }).first().click();
    await page.getByText('Last GPS fix').waitFor();
    await page.goBack();
    await page.getByRole('heading', { name: 'Route 534' }).waitFor();
  });
  await check('stop arrivals: sorted, estimates marked, no number invented', async () => {
    await page.goto(`${BASE}/?stop=s1`);
    await page.getByRole('heading', { name: 'Next buses' }).waitFor();
    const rows = page.locator('.arr-list li');
    await rows.first().waitFor();
    const text = (await rows.allInnerTexts()).map(t => t.replace(/\s+/g, ' '));
    assert.match(text[0], /534.*2.*min.*Live prediction/);
    assert.match(text[1], /534.*≈.*6.*Estimate from recent trips/);
    assert.match(text[2], /990.*No estimate/);
  });
  await check('arrival alert fires once, in-app, for a bus within the threshold', async () => {
    await page.getByRole('button', { name: 'Alert me when a bus is near' }).click();
    await page.getByRole('combobox', { name: 'When it is' }).selectOption('3');
    await page.getByRole('button', { name: 'Save alert' }).click();
    await page.getByText(/Alert when any tracked bus is ≤ 3 min away/).waitFor();
    await page.locator('.toast').getByText(/Route 534 2 min away/).waitFor({ timeout: 10000 });
    assert.equal(await page.locator('.toast').count(), 1);
  });
  await check('favourite stop persists across reload', async () => {
    await page.getByRole('button', { name: `Save stop ${STOP.name}` }).click();
    await page.reload();
    await page.goto(`${BASE}/saved`);
    await page.getByRole('heading', { name: STOP.name }).waitFor();
  });
  await check('journey timeline renders with its basis', async () => {
    await page.goto(`${BASE}/journey?from=${encodeURIComponent('28.708,77.181|Home')}&to=${encodeURIComponent('28.671,77.221|Office')}`);
    await page.locator('.jr-card').waitFor();
    const t = await page.locator('.timeline').innerText();
    assert.match(t, /Board at Azadpur Terminal/);
    assert.match(t, /timetable ride time/);
    assert.match(await page.locator('.jr-summary').innerText(), /waiting for 1 bus/);
  });
  assert.deepEqual(errors, []);

  const fallback = await newPage(browser, { patterns404: true, journey404: true });
  await check('older backend: stops-only route and journey-unavailable message', async () => {
    await fallback.page.goto(`${BASE}/?route=534`);
    await fallback.page.getByText(/doesn.t provide their order/).waitFor();
    await fallback.page.goto(`${BASE}/journey?from=${encodeURIComponent('28.708,77.181|A')}&to=${encodeURIComponent('28.671,77.221|B')}`);
    await fallback.page.getByText(/isn.t available on this server yet/).waitFor();
  });

  const down = await newPage(browser, { down: true });
  await check('unreachable backend shows the waking banner and an error state', async () => {
    await down.page.goto(BASE);
    await down.page.getByText(/Can.t reach the live bus feed/).waitFor();
    await down.page.getByText(/Connecting to the live server/).waitFor({ timeout: 15000 });
  });

  const mobile = await newPage(browser, { viewport: { width: 375, height: 740 } });
  await check('phone layout: no horizontal scroll, tab bar visible', async () => {
    for (const path of ['/', '/nearby', '/journey', '/saved']) {
      await mobile.page.goto(BASE + path);
      await mobile.page.waitForTimeout(400);
      assert.equal(await mobile.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true, path);
      assert.equal(await mobile.page.locator('.tabbar').isVisible(), true);
    }
  });
  console.log(`\n${passed} browser checks passed`);
} finally {
  await browser?.close();
  server.kill();
}
