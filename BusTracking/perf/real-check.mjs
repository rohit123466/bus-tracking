// Real-backend checks of the live map (no fixtures): poll alignment, revalidation, leaks, route filter.
// usage: node real-check.mjs <frontendDir> <distDir> <label> <apiBase> [seconds] [--full]
import { spawn } from 'node:child_process';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const [frontend, distDir, label, api, seconds = '90', full] = process.argv.slice(2);
const { chromium } = await import(pathToFileURL(path.join(frontend, 'node_modules/@playwright/test/index.mjs')).href);
const PORT = 5190, BASE = `http://127.0.0.1:${PORT}`;
const REGIONAL = `${api}/transit/buses/regional?region=ANY&routeNumber=`;
const server = spawn(process.execPath, [path.join(frontend, 'node_modules/vite/bin/vite.js'), 'preview', '--host', '127.0.0.1', '--port', String(PORT), '--strictPort', '--outDir', distDir], { cwd: frontend, stdio: 'ignore' });
for (let i = 0; i < 100; i++) { try { await (await fetch(BASE)).text(); break; } catch { await new Promise(r => setTimeout(r, 150)); } }
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };

// Side channel: when does each GPS fix (vehicle|timestamp) first become available from the server?
const firstAvailable = new Map();
const fixes = body => (body?.buses ?? []).map(b => `${b.vehicleId}|${b.lastUpdatedEpochSeconds}`);
let watching = true;
const watcher = (async () => {
  while (watching) {
    try {
      const r = await fetch(REGIONAL);
      const now = Date.now();
      for (const k of fixes(await r.json())) if (!firstAvailable.has(k)) firstAvailable.set(k, now);
    } catch { /* keep watching */ }
    await new Promise(r => setTimeout(r, 250));
  }
})();
await new Promise(r => setTimeout(r, 1500)); // fixes already there before the tab opens are not "new"
const preexisting = new Set(firstAvailable.keys());

const browser = await chromium.launch({ channel: 'msedge' });
const out = { label };
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 820 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  // Map tiles are aborted on purpose (offline-safe, no load on OSM); their console errors are ours.
  page.on('console', m => { if (m.type() === 'error' && !/tile\.openstreetmap/.test(m.location()?.url ?? '')) errors.push(m.text()); });
  await page.route(/tile\.openstreetmap|nominatim/, r => r.abort());
  const polls = [];
  const received = new Map();
  page.on('response', async res => {
    if (!res.url().includes('/transit/buses/regional')) return;
    const at = Date.now();
    const h = await res.allHeaders();
    const route = new URL(res.url()).searchParams.get('routeNumber');
    polls.push({ at, status: res.status(), hint: h['x-next-update-in'], route });
    if (!route) try { for (const k of fixes(await res.json())) if (!received.has(k)) received.set(k, at); } catch { /* 304 without body */ }
  });
  await page.goto(BASE);
  await page.getByText(/^Live feed ·/).waitFor({ timeout: 60000 });
  await page.waitForTimeout(Number(seconds) * 1000);

  // For each GPS fix that appeared on the server while the tab was open: how long until the tab had it?
  const delays = [];
  for (const [k, at] of received) {
    if (preexisting.has(k)) continue;
    const avail = firstAvailable.get(k);
    if (avail != null) delays.push(Math.max(0, at - avail) / 1000);
  }
  const overview = polls.filter(p => !p.route);
  out.overview = {
    polls: overview.length, perMinute: +(overview.length / (Number(seconds) / 60)).toFixed(1),
    statuses: overview.reduce((m, p) => ((m[p.status] = (m[p.status] || 0) + 1), m), {}),
    hintSeen: overview.some(p => p.hint != null),
    newFixesReceived: delays.length,
    delayAfterServerHadFixS: { mean: +(delays.reduce((a, b) => a + b, 0) / delays.length).toFixed(2), p50: +pct(delays, 0.5)?.toFixed(1), p90: +pct(delays, 0.9)?.toFixed(1), max: +Math.max(...delays).toFixed(1) },
  };

  if (full) {
    // Map and status pill agree with the answer; one marker layer per bus.
    const pill = await page.locator('.status-pill').innerText();
    out.pill = pill.replace(/\s+/g, ' ');
    // Mount/unmount: Explore <-> Nearby ten times, then compare listeners and heap.
    const cdp = await ctx.newCDPSession(page);
    const listeners = async () => {
      const count = async expr => {
        const { result } = await cdp.send('Runtime.evaluate', { expression: expr });
        const { listeners: l } = await cdp.send('DOMDebugger.getEventListeners', { objectId: result.objectId });
        return l.length;
      };
      return { window: await count('window'), document: await count('document') };
    };
    const heap = async () => { await cdp.send('HeapProfiler.collectGarbage'); return (await cdp.send('Runtime.getHeapUsage')).usedSize; };
    const l0 = await listeners(), h0 = await heap();
    for (let i = 0; i < 10; i++) {
      await page.getByRole('link', { name: /Nearby/i }).first().click();
      await page.waitForTimeout(400);
      await page.getByRole('link', { name: /Explore|Map/i }).first().click();
      await page.getByText(/^Live feed ·/).waitFor({ timeout: 30000 });
    }
    await page.waitForTimeout(1500);
    const l1 = await listeners(), h1 = await heap();
    out.mountCycles = { listenersBefore: l0, listenersAfter: l1, heapMBBefore: +(h0 / 1048576).toFixed(1), heapMBAfter: +(h1 / 1048576).toFixed(1), leafletMaps: await page.locator('.leaflet-container').count() };

    // Route filter on real data: every bus returned is that route (by feed number or likely route).
    const routeResp = page.waitForResponse(r => r.url().includes('/transit/buses/regional') && r.url().includes('routeNumber=901'));
    await page.goto(`${BASE}/?route=901`);
    const body = await (await routeResp).json();
    const wrong = body.buses.filter(b => b.routeCode !== '901' && !(b.likelyRoutes ?? []).includes('901'));
    assert.equal(wrong.length, 0, 'route filter returned other routes');
    await page.getByRole('heading', { name: 'Route 901' }).waitFor();
    out.route901 = { buses: body.buses.length, panel: (await page.locator('.side-body').innerText()).split('\n').slice(0, 3).join(' | ') };
    if (body.buses.length) {
      const id = body.buses[0].vehicleId;
      await page.goto(`${BASE}/?route=901&bus=${encodeURIComponent(id)}`);
      await page.getByText('Last GPS fix').waitFor({ timeout: 30000 });
      out.route901.selectedPins = await page.locator('.pin-bus-selected').count();
    }
  }
  out.pageErrors = errors;
} finally {
  watching = false;
  await watcher;
  await browser.close();
  server.kill();
}
console.log(JSON.stringify(out, null, 1));
