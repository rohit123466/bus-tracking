// Live-map rendering benchmark. Serves the frontend's production build (vite preview) and replays
// recorded real /buses/regional snapshots through Playwright (positions re-dated to "now" with their
// real relative ages), then counts Leaflet work per update and main-thread time.
// usage: node map-bench.mjs <frontendDir> <snapshotDir> <label> [seconds]
import { spawn } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [frontend, snapDir, label = 'run', seconds = '60', distDir = 'dist'] = process.argv.slice(2);
const { chromium } = await import(pathToFileURL(path.join(frontend, 'node_modules/@playwright/test/index.mjs')).href);
const PORT = 5190;
const BASE = `http://127.0.0.1:${PORT}`;
const snaps = readdirSync(snapDir).filter(f => f.endsWith('.json')).sort().map(f => JSON.parse(readFileSync(path.join(snapDir, f), 'utf8')));

const server = spawn(process.execPath, [path.join(frontend, 'node_modules/vite/bin/vite.js'), 'preview', '--host', '127.0.0.1', '--port', String(PORT), '--strictPort', '--outDir', distDir], { cwd: frontend, stdio: 'ignore' });
for (let i = 0; i < 100; i++) { try { await (await fetch(BASE)).text(); break; } catch { await new Promise(r => setTimeout(r, 150)); } }

// Counts Leaflet calls from inside the page: Leaflet assigns window.L when it loads.
const INSTRUMENT = () => {
  const counts = { setLatLng: 0, setIcon: 0, setStyle: 0, on: 0, off: 0, addLayer: 0, removeLayer: 0, setLatLngs: 0 };
  window.__counts = counts;
  let real;
  Object.defineProperty(window, 'L', {
    configurable: true,
    get: () => real,
    set: (L) => {
      real = L;
      const wrap = (proto, name, key = name) => {
        const orig = proto[name];
        proto[name] = function (...a) { counts[key]++; return orig.apply(this, a); };
      };
      wrap(L.Marker.prototype, 'setLatLng');
      wrap(L.CircleMarker.prototype, 'setLatLng');
      wrap(L.Marker.prototype, 'setIcon');
      wrap(L.Path.prototype, 'setStyle');
      wrap(L.Polyline.prototype, 'setLatLngs');
      wrap(L.Evented.prototype, 'on');
      wrap(L.Evented.prototype, 'off');
      wrap(L.Map.prototype, 'addLayer');
      wrap(L.Map.prototype, 'removeLayer');
      L.Map.addInitHook(function () { window.__map = this; });
    },
  });
};

async function scenario(browser, name, url, zoomTo) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 820 } });
  const page = await ctx.newPage();
  await page.addInitScript(INSTRUMENT);
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  let served = 0, regional = 0;
  await page.route(/tile\.openstreetmap|nominatim/, r => r.abort());
  await page.route(u => new URL(u).pathname.startsWith('/api/'), async route => {
    const u = new URL(route.request().url());
    const now = Math.floor(Date.now() / 1000);
    const cors = { 'access-control-allow-origin': '*' };
    if (u.pathname === '/api/transit/buses/regional') {
      regional++;
      const snap = snaps[served++ % snaps.length];
      const shift = now - snap.fetchedAt;
      const body = { ...snap, fetchedAt: now, buses: snap.buses.map(b => ({ ...b, lastUpdatedEpochSeconds: b.lastUpdatedEpochSeconds + shift })) };
      return route.fulfill({ json: body, headers: cors });
    }
    if (u.pathname === '/api/transit/alerts') return route.fulfill({ json: { alerts: [], feedStatus: 'OK', fetchedAt: now }, headers: cors });
    return route.fulfill({ status: 404, json: { message: 'Not found' }, headers: cors });
  });
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Performance.enable');
  const t0 = Date.now();
  await page.goto(`${BASE}${url}`);
  await page.getByText(/^Live feed ·/).waitFor({ timeout: 30000 });
  const loadMs = Date.now() - t0;
  if (zoomTo) {
    await page.evaluate(([lat, lng, z]) => { window.__map.setView([lat, lng], z, { animate: false }); }, zoomTo);
    await page.waitForTimeout(1500);
  }
  const metric = async () => Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(m => [m.name, m.value]));
  const c0 = await page.evaluate(() => ({ ...window.__counts }));
  const m0 = await metric();
  const r0 = regional;
  await page.waitForTimeout(Number(seconds) * 1000);
  const c1 = await page.evaluate(() => ({ ...window.__counts }));
  const m1 = await metric();
  const polls = regional - r0;
  const markers = await page.evaluate(() => ({
    canvasDots: document.querySelectorAll('.leaflet-overlay-pane canvas').length,
    pins: document.querySelectorAll('.leaflet-marker-pane .pin').length,
  }));
  const delta = Object.fromEntries(Object.keys(c1).map(k => [k, c1[k] - c0[k]]));
  const out = {
    label, scenario: name, seconds: Number(seconds), polls, initialLoadMs: loadMs,
    leafletCallsPerPoll: Object.fromEntries(Object.entries(delta).map(([k, v]) => [k, polls ? Math.round(v / polls) : v])),
    mainThreadMs: { task: Math.round((m1.TaskDuration - m0.TaskDuration) * 1000), script: Math.round((m1.ScriptDuration - m0.ScriptDuration) * 1000), layout: Math.round((m1.LayoutDuration - m0.LayoutDuration) * 1000), recalcStyle: Math.round((m1.RecalcStyleDuration - m0.RecalcStyleDuration) * 1000) },
    jsHeapMB: Math.round(m1.JSHeapUsedSize / 1048576), domNodes: m1.Nodes, ...markers, pageErrors: errors,
  };
  await ctx.close();
  return out;
}

let browser;
try {
  browser = await chromium.launch({ channel: 'msedge' });
  const results = [];
  results.push(await scenario(browser, 'overview (all buses, zoom 12)', '/', null));
  results.push(await scenario(browser, 'street level (labelled pins)', '/', [28.665, 77.23, 15]));
  console.log(JSON.stringify(results, null, 1));
} finally {
  await browser?.close();
  server.kill();
}
