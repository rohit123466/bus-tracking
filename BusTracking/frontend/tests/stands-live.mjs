// Browser check of "Search bus stand & live arrivals" (/stands) against a REAL running backend and
// the live Delhi feed: `npm run dev` + backend on :8080, then `node tests/stands-live.mjs [outDir]`.
// Uses the installed Microsoft Edge (no Playwright browser download). Live data changes every run,
// so this checks behaviour and consistency, not fixed values. Screenshots go to outDir.
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';

const BASE = process.env.APP_URL || 'http://localhost:5173';
const OUT = process.argv[2] || 'stands-shots';
mkdirSync(OUT, { recursive: true });

const results = [];
const check = (name, ok, detail = '') => { results.push({ name, ok }); console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}`); };

const browser = await chromium.launch({ channel: 'msedge' });

async function suggestions(page, text) {
  const input = page.getByRole('combobox', { name: 'Search your bus stand' });
  await input.fill('');
  await input.fill(text);
  await page.waitForTimeout(400);
  await page.waitForFunction(() => !document.querySelector('.combo-status')?.textContent?.includes('Searching'), null, { timeout: 15000 });
  const options = await page.locator('[role="option"]').allInnerTexts();
  const status = await page.locator('.combo-status').allInnerTexts();
  return { options: options.map(o => o.replace(/\s+/g, ' ').trim()), status: status.join(' ') };
}

async function openStand(page, text) {
  await suggestions(page, text);
  const option = page.locator('[role="option"]').first();
  const label = (await option.locator('span.grow').evaluate(el => el.firstChild.textContent)).trim();
  await option.click();
  await waitForBoard(page, label);
  return label;
}

/** Until the board shows the stand called `name` with its summary (not the previous stand's). */
async function waitForBoard(page, name) {
  await page.waitForFunction(n => document.querySelector('#stand-name')?.textContent === n && document.querySelector('.stand-kpis'),
    name, { timeout: 30000 });
}

async function board(page) {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('section[aria-labelledby="live-h"] tbody tr')].map(tr =>
      [...tr.querySelectorAll('td')].map(td => td.innerText.replace(/\s+/g, ' ').trim()));
    const sched = document.querySelectorAll('section[aria-labelledby="sched-h"] tbody tr').length;
    const kpis = [...document.querySelectorAll('.stand-kpi')].map(k => k.innerText.replace(/\s+/g, ' ').trim());
    return {
      name: document.querySelector('#stand-name')?.textContent,
      rows, sched, kpis,
      notice: document.querySelector('.notice')?.innerText ?? null,
      refresh: document.querySelector('.refresh-ind')?.innerText.replace(/\s+/g, ' ').trim(),
      // Buses are drawn on a canvas (preferCanvas) when zoomed out, pins when zoomed in.
      mapCanvas: !!document.querySelector('.stand-map canvas'),
      standPin: document.querySelectorAll('.stand-map .leaflet-marker-icon').length,
      overflow: document.documentElement.scrollWidth - window.innerWidth,
    };
  });
}

// ── Desktop ────────────────────────────────────────────────────────────────
{
  const page = await browser.newPage({ viewport: { width: 1366, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error' && !/tile\.openstreetmap|favicon/.test(m.text())) errors.push(m.text()); });
  await page.goto(`${BASE}/stands`);
  const placeholder = page.getByPlaceholder('Search your bus stand (e.g. Kashmere Gate, Azadpur, Rohini Sector 18)', { exact: true });
  await placeholder.waitFor({ timeout: 15000 }).catch(() => {});
  check('placeholder text', await placeholder.count() === 1);

  for (const q of ['Kashmere Gate', 'Azadpur', 'Rohini', 'kashm', 'AZAD', 'rohini sect']) {
    const s = await suggestions(page, q);
    const dupes = s.options.length - new Set(s.options).size;
    check(`suggestions for "${q}"`, s.options.length > 0 && dupes === 0, `${s.options.length} shown, ${dupes} duplicates: ${s.options.slice(0, 3).join(' | ')}`);
  }
  const invalid = await suggestions(page, 'xyzzy nagar');
  check('invalid stand shows a no-match message', invalid.options.length === 0 && /No bus stand matches/.test(invalid.status), invalid.status);

  await openStand(page, 'Kashmere Gate');
  const first = await board(page);
  check('Kashmere Gate board shows live buses', first.rows.length > 1, `${first.name}: ${first.rows.length} rows; kpis: ${first.kpis.join(' / ')}`);
  console.log('  first rows:', first.rows.slice(0, 3).map(r => r.join(' ¦ ')));
  check('every live row has route, ETA cell and GPS time', first.rows.every(r => r.length === 7 && r[0] && r[6]));
  check('map draws the stand and the bus layer', first.mapCanvas && first.standPin >= 1, `canvas ${first.mapCanvas}, pins ${first.standPin}`);
  await page.screenshot({ path: join(OUT, 'desktop-kashmere-gate.png'), fullPage: false });

  // Auto-refresh: wait for the next poll (≤15 s) and see the indicator and data move.
  const updatedBefore = await page.evaluate(() => performance.getEntriesByType('resource').filter(e => e.name.includes('/stands/')).length);
  await page.waitForTimeout(16000);
  const updatedAfter = await page.evaluate(() => performance.getEntriesByType('resource').filter(e => e.name.includes('/stands/')).length);
  const second = await board(page);
  const changed = JSON.stringify(first.rows.slice(0, 10)) !== JSON.stringify(second.rows.slice(0, 10));
  check('board re-polls within ~15 s', updatedAfter > updatedBefore, `${updatedAfter - updatedBefore} new requests; refresh: ${second.refresh}; rows changed: ${changed}`);

  await openStand(page, 'Azadpur');
  const az = await board(page);
  check('Azadpur board shows live buses', az.rows.length > 0, `${az.name}: ${az.rows.length} rows; kpis: ${az.kpis.join(' / ')}`);
  await page.screenshot({ path: join(OUT, 'desktop-azadpur.png') });

  await openStand(page, 'Rohini Sec 18');
  const ro = await board(page);
  check('Rohini Sec 18 board loads', !!ro.name, `${ro.name}: ${ro.rows.length} live, ${ro.sched} scheduled; notice: ${ro.notice}`);

  // No live GPS for a stand: find a real one right now (the server then falls back to the
  // timetable) and check the page labels it as scheduled, never as live.
  const API = process.env.API_URL || 'http://localhost:8080/api';
  let quiet = null;
  for (const q of ['mandir', 'village', 'school', 'chowk']) {
    for (const s of await (await fetch(`${API}/transit/stands/search?query=${q}&limit=30`)).json()) {
      const r = await (await fetch(`${API}/transit/stands/${s.id}/arrivals`)).json();
      if (r.liveState !== 'LIVE' && r.scheduled.length) { quiet = r; break; }
    }
    if (quiet) break;
  }
  let simulated = false;
  if (!quiet) {
    // Every sampled stand has live buses now. Simulate the feed being down for a stand whose real
    // answer has timetable rows: the server then sends no live rows, feedAvailable=false and the
    // notice (its own logic is covered by StandServiceTest).
    for (const q of ['kashmere gate', 'azadpur', 'rohini']) {
      for (const s of await (await fetch(`${API}/transit/stands/search?query=${encodeURIComponent(q)}&limit=10`)).json()) {
        const r = await (await fetch(`${API}/transit/stands/${s.id}/arrivals`)).json();
        if (r.scheduled.length) { quiet = r; break; }
      }
      if (quiet) break;
    }
    simulated = true;
    await page.route('**/api/transit/stands/*/arrivals', async route => {
      const res = await route.fetch();
      const body = await res.json();
      await route.fulfill({ response: res, json: { ...body, arrivals: [], feedAvailable: false, liveState: 'FEED_UNAVAILABLE',
        notice: 'Live location unavailable – Showing scheduled arrival information',
        summary: { ...body.summary, liveBusCount: 0, approachingCount: 0, nextArrival: null } } });
    });
  }
  console.log(`  no-live case: ${simulated ? 'SIMULATED feed outage' : 'real stand with no tracked bus'} at ${quiet?.stopName}`);
  if (!quiet) check('a stand with timetable rows was found', false);
  else {
    await page.goto(`${BASE}/stands?stand=${encodeURIComponent(quiet.standId)}`);
    await waitForBoard(page, quiet.stopName);
    const down = await board(page);
    check('no live GPS: notice and scheduled list, no live rows',
      down.rows.length === 0 && /Live location unavailable – Showing scheduled arrival information/.test(down.notice ?? '') && down.sched > 0,
      `${down.name}: notice "${down.notice}"; scheduled rows: ${down.sched}`);
    const labelled = await page.locator('section[aria-labelledby="sched-h"]').innerText();
    check('scheduled rows are labelled timetable, not live', /Scheduled · not live/.test(labelled) && /Timetable/.test(labelled) && !/Live GPS/.test(labelled));
    await page.screenshot({ path: join(OUT, 'desktop-no-live-gps.png') });
  }
  if (simulated) await page.unroute('**/api/transit/stands/*/arrivals');

  check('no page errors (desktop)', errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
}

// ── Phone ──────────────────────────────────────────────────────────────────
{
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(`${BASE}/stands`);
  await openStand(page, 'Azadpur');
  const b = await board(page);
  check('phone: board renders without horizontal scroll', b.overflow <= 0 && b.rows.length > 0, `overflow ${b.overflow}px, ${b.rows.length} rows`);
  await page.screenshot({ path: join(OUT, 'phone-azadpur-top.png') });
  await page.locator('section[aria-labelledby="live-h"]').scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(OUT, 'phone-azadpur-list.png') });
  check('no page errors (phone)', errors.length === 0, errors.slice(0, 3).join(' | '));
  await page.close();
}

await browser.close();
const failed = results.filter(r => !r.ok).length;
console.log(`\n${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
