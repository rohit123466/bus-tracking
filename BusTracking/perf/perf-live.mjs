// Samples /api/transit/buses/regional and reports where live-position delay comes from.
// usage: node perf-live.mjs <baseUrl> <seconds> [sampleMs]
const [base = 'http://localhost:8080', seconds = '180', sampleMs = '2000'] = process.argv.slice(2);
const url = `${base}/api/transit/buses/regional?region=ANY&routeNumber=`;
const end = Date.now() + Number(seconds) * 1000;
const samples = [];
const lastStamp = new Map();   // vehicleId -> last GPS timestamp seen
const reportGaps = [];         // seconds between successive distinct GPS fixes of one vehicle
const firstSeen = new Map();   // `${vehicle}|${stamp}` -> wall time the API first returned it
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : NaN; };

while (Date.now() < end) {
  const t0 = performance.now();
  let res, body;
  let text;
  try { res = await fetch(url); text = await res.text(); body = JSON.parse(text); } catch (e) { samples.push({ error: String(e) }); continue; }
  const ms = performance.now() - t0;
  const wire = Number(res.headers.get('content-length')) || Buffer.byteLength(text);
  const wall = Date.now() / 1000;
  const ages = body.buses.map(b => body.fetchedAt - b.lastUpdatedEpochSeconds);
  for (const b of body.buses) {
    const prev = lastStamp.get(b.vehicleId);
    if (prev != null && b.lastUpdatedEpochSeconds > prev) reportGaps.push(b.lastUpdatedEpochSeconds - prev);
    lastStamp.set(b.vehicleId, b.lastUpdatedEpochSeconds);
    const k = `${b.vehicleId}|${b.lastUpdatedEpochSeconds}`;
    if (!firstSeen.has(k)) firstSeen.set(k, wall);
  }
  samples.push({ ms, wire, fetchedAt: body.fetchedAt, n: body.buses.length, ageMed: pct(ages, 0.5), wall });
  await new Promise(r => setTimeout(r, Math.max(0, Number(sampleMs) - (performance.now() - t0))));
}

const ok = samples.filter(s => !s.error);
const fetches = [...new Set(ok.map(s => s.fetchedAt))].sort();
const cadence = fetches.slice(1).map((f, i) => f - fetches[i]);
// For fixes first seen after the warm-up sample: how old the fix already was when the API first served it.
const t0 = ok[0]?.wall ?? 0;
const ageWhenServed = [...firstSeen.entries()].filter(([, w]) => w > t0 + 1).map(([k, w]) => w - Number(k.split('|')[1]));
const out = {
  url, samples: ok.length, errors: samples.length - ok.length,
  requestMs: { p50: +pct(ok.map(s => s.ms), 0.5).toFixed(1), p95: +pct(ok.map(s => s.ms), 0.95).toFixed(1), max: +Math.max(...ok.map(s => s.ms)).toFixed(1) },
  buses: { p50: pct(ok.map(s => s.n), 0.5) },
  wireKB: { p50: Math.round(pct(ok.map(s => s.wire), 0.5) / 1024) },
  serverFeedPollIntervalS: { n: cadence.length, p50: pct(cadence, 0.5), p95: pct(cadence, 0.95) },
  gpsAgeAtServerFetchS: { p50: pct(ok.map(s => s.ageMed), 0.5) },
  vehicleReportIntervalS: { n: reportGaps.length, p50: pct(reportGaps, 0.5), p90: pct(reportGaps, 0.9) },
  gpsAgeWhenFirstServedS: { n: ageWhenServed.length, p50: +pct(ageWhenServed, 0.5).toFixed(1), p90: +pct(ageWhenServed, 0.9).toFixed(1) },
};
console.log(JSON.stringify(out, null, 1));
