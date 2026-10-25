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
const upcoming = (routeNumber, vehicleId, etaMinutes, availability, extra = {}) => ({
  routeNumber, vehicleId, etaMinutes, availability, label: '', reason: etaMinutes == null ? 'Route geometry unavailable.' : null,
  remainingStops: 3, lastGpsUpdate: now() - 10, busLatitude: 28.70, busLongitude: 77.17, speedKmh: 18, distanceMeters: 900,
  routeCheck: 'ON_ROUTE', ...extra,
});

function fixtures({ patterns404 = false, journey404 = false } = {}) {
  return {
    // Route 106 deliberately includes look-alike route numbers and a duplicate record, as a
    // contaminated answer would.
    '/api/transit/buses/regional': url => ({
      buses: url.searchParams.get('routeNumber') === '106'
        ? [bus('R106', '106', 5, { routeCheck: 'ON_ROUTE' }), bus('R106A', '106A', 5), bus('R1060', '1060', 5), bus('R160', '160', 5),
          bus('R106', '106', 9, { busLatitude: 28.71 })]
        // Route 427: one bus by its feed number on DTC's current path, one whose feed number is stale.
        : url.searchParams.get('routeNumber') === '427'
        ? [bus('R427', '427', 5, { routeCheck: 'ON_OFFICIAL_ROUTE' }),
          bus('STALE427', '836LNKSTL', 6, { routeCheck: 'OFF_ROUTE', likelyRoutes: ['427'], nextStopName: null, nextStopEtaMinutes: null })]
        : [bus('LIVE1', '534', 5, { routeCheck: 'ON_ROUTE' }), bus('DELAYED1', '534', 70, { routeCheck: 'TRIP_MATCH' }),
          bus('STALE1', '534', 400), bus('OFF1', '534', 8, { routeCheck: 'OFF_ROUTE', nextStopName: null, nextStopEtaMinutes: null })],
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
        upcoming: [upcoming('534', 'LIVE1', 6.2, 'LEARNED_ROUTE_ESTIMATE'), upcoming('990', 'X9', null, 'UNAVAILABLE'), upcoming('534', 'TU1', 2.1, 'TRIP_UPDATE'),
          upcoming('534', 'OLD1', 30, 'LEARNED_ROUTE_ESTIMATE', { lastGpsUpdate: now() - 200, routeCheck: 'OFF_ROUTE' })] }],
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
    geolocation: { latitude: 28.708, longitude: 77.181, accuracy: 15 }, permissions: opts.noLocation ? [] : ['geolocation'],
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const table = fixtures(opts);
  await page.route(u => new URL(u).pathname.startsWith('/api/'), async route => {
    if (opts.down) return route.abort('connectionrefused');
    const url = new URL(route.request().url());
    const handler = table[url.pathname];
    const body = handler ? handler(url) : 404;
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
    await page.getByText(/^Live feed · 3 buses on route 534 \(1 with an unconfirmed route\)/).waitFor();
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
    // Its GPS fix is 200 s old: the 30 min estimate is withdrawn, and its route number is flagged.
    assert.match(text[2], /534.*Route unconfirmed.*Out of date/);
    assert.doesNotMatch(text[2], /\b30\b/);
    assert.match(text[3], /990.*No estimate/);
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
    const summary = await page.locator('.jr-summary').innerText();
    assert.match(summary, /waiting for 1 bus/);
    assert.match(summary, /Timetable ride times/);
    assert.match(summary, /Walk ≈ 250 m \(straight line\)/);
  });
  await check('route with an unconfirmed bus: flagged in list and detail, can be hidden everywhere', async () => {
    await page.goto(`${BASE}/?route=534`);
    await page.getByText(/1 bus reported on route 534 is more than 500 m/).waitFor();
    const items = await page.locator('section[aria-labelledby="rt-buses"] li').allInnerTexts();
    assert.match(items.at(-1), /OFF1[\s\S]*Route unconfirmed/, 'unconfirmed bus is listed last');
    await page.getByRole('button', { name: /OFF1/ }).click();
    await page.getByText('Route number unconfirmed.').waitFor();
    await page.goBack();
    await page.getByRole('checkbox', { name: /Hide buses whose route number is unconfirmed/ }).check();
    await page.getByText(/^Live feed · 2 buses on route 534 \(1 with an unconfirmed route hidden\)/).waitFor();
    assert.equal(await page.getByRole('button', { name: /OFF1/ }).count(), 0);
    await page.getByText(/1 bus with an unconfirmed route is hidden/).waitFor();
    await page.getByRole('checkbox', { name: /Hide buses whose route number is unconfirmed/ }).uncheck();
  });
  await check('stale feed number: bus listed and labelled by its likely route, with the feed number kept visible', async () => {
    await page.goto(`${BASE}/?route=427`);
    await page.getByText(/^Live feed · 2 buses on route 427/).waitFor();
    const items = await page.locator('section[aria-labelledby="rt-buses"] li').allInnerTexts();
    assert.match(items.join('\n'), /STALE427[\s\S]*Likely route 427 \(from GPS\)/);
    await page.getByRole('button', { name: /STALE427/ }).click();
    await page.getByRole('heading', { name: /Likely route 427/ }).waitFor();
    await page.getByText('feed says 836LNKSTL').waitFor();
    await page.getByText(/The live feed calls this bus route 836LNKSTL/).waitFor();
    await page.getByRole('img', { name: /Bus likely on route 427 \(estimated from GPS\)/ }).waitFor();
    await page.goBack();
    await page.getByRole('button', { name: /R427/ }).click();
    await page.getByText('On this route’s current DTC path').waitFor();
  });
  await check('route 106: look-alike routes and a duplicate vehicle never reach the map or list', async () => {
    await page.goto(`${BASE}/?route=106`);
    await page.getByText(/^Live feed · 1 bus on route 106 ·/).waitFor();
    const list = await page.locator('.side-body').innerText();
    for (const id of ['R106A', 'R1060', 'R160']) assert.ok(!list.includes(id), id);
    assert.equal(await page.getByRole('button', { name: /R106/ }).count(), 1);
  });
  await check('find buses near me: blue location marker, nearest stand with arrivals, reset view', async () => {
    await page.goto(BASE);
    await page.getByRole('button', { name: 'Find buses near me' }).click();
    await page.locator('.pin-me').waitFor();
    assert.equal(await page.locator('.pin-me').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(26, 115, 232)');
    assert.equal(await page.locator('.pin-me.pin-bus, .pin-bus .pin-me').count(), 0, 'location marker is not a bus marker');
    await page.getByRole('heading', { name: 'Near you' }).waitFor();
    await page.getByRole('button', { name: /Azadpur Terminal/ }).first().waitFor();
    await page.getByRole('button', { name: 'Reset map view' }).click();
  });
  await check('keyboard: map controls are reachable with Tab and show a focus outline', async () => {
    await page.goto(BASE);
    let label = '';
    for (let i = 0; i < 80 && label !== 'Reset map view'; i++) {
      await page.keyboard.press('Tab');
      label = await page.evaluate(() => document.activeElement?.getAttribute('aria-label') ?? '');
    }
    assert.equal(label, 'Reset map view');
    assert.notEqual(await page.evaluate(() => getComputedStyle(document.activeElement).outlineStyle), 'none');
  });
  await check('nearby: exact route filter narrows stops and arrivals', async () => {
    await page.goto(`${BASE}/nearby`);
    await page.getByRole('heading', { name: STOP.name }).waitFor();
    assert.match(await page.locator('.stand').innerText(), /Stop ID s1/);
    await page.getByRole('textbox', { name: /Only route/ }).fill('990');
    const card = await page.locator('.stand').innerText();
    assert.match(card, /990/);
    assert.doesNotMatch(card, /534/);
    await page.getByRole('textbox', { name: /Only route/ }).fill('106');
    await page.getByText(/No stop within 500 m lists route 106/).waitFor();
  });
  await check('language: Hindi labels for navigation and map controls, html lang follows', async () => {
    await page.goto(BASE);
    await page.getByRole('button', { name: /Switch to Hindi/ }).click();
    await page.locator('.topnav').getByText('आस-पास').waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.lang), 'hi');
    await page.getByRole('button', { name: 'मेरे पास की बसें खोजें' }).waitFor();
    await page.getByRole('button', { name: /अंग्रेज़ी में बदलें/ }).click();
    await page.locator('.topnav').getByText('Nearby').waitFor();
  });
  assert.deepEqual(errors, []);

  const denied = await newPage(browser, { noLocation: true });
  await check('location denied: clear message, no invented position, map and route search still work', async () => {
    await denied.page.goto(BASE);
    await denied.page.getByRole('button', { name: 'Find buses near me' }).click();
    await denied.page.getByText(/Location access is blocked|could not determine|took too long/).waitFor({ timeout: 25000 });
    assert.equal(await denied.page.locator('.pin-me').count(), 0);
    await denied.page.goto(`${BASE}/?route=534`);
    await denied.page.getByText(/^Live feed · 3 buses on route 534/).waitFor();
  });
  assert.deepEqual(denied.errors, []);

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
