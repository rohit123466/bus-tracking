// Polls the OTD VehiclePositions feed and reports how often its header timestamp changes.
// usage: DTC_API_KEY=... node probe-source.mjs <count> <spacingMs>
import { createHash } from 'node:crypto';
const [count = '30', spacing = '3000'] = process.argv.slice(2);
const url = `https://otd.delhi.gov.in/api/realtime/VehiclePositions.pb?key=${encodeURIComponent(process.env.DTC_API_KEY ?? '')}`;

function varint(buf, pos) {
  let v = 0n, shift = 0n, b;
  do { b = buf[pos++]; v |= BigInt(b & 0x7f) << shift; shift += 7n; } while (b & 0x80);
  return [v, pos];
}
/** FeedMessage.header (field 1) -> FeedHeader.timestamp (field 3). */
function headerTimestamp(buf) {
  let [tag, p] = varint(buf, 0);
  if (Number(tag) !== 0x0a) return null;
  let len; [len, p] = varint(buf, p);
  const end = p + Number(len);
  while (p < end) {
    let t; [t, p] = varint(buf, p);
    const field = Number(t) >> 3, wire = Number(t) & 7;
    if (wire === 0) { let v; [v, p] = varint(buf, p); if (field === 3) return Number(v); }
    else if (wire === 2) { let l; [l, p] = varint(buf, p); p += Number(l); }
    else return null;
  }
  return null;
}

const rows = [];
for (let i = 0; i < Number(count); i++) {
  const t0 = performance.now();
  try {
    const res = await fetch(url);
    const buf = Buffer.from(await res.arrayBuffer());
    const ms = performance.now() - t0;
    rows.push({ at: Date.now() / 1000, ms, status: res.status, bytes: buf.length, header: headerTimestamp(buf), hash: createHash('md5').update(buf).digest('hex').slice(0, 8) });
  } catch (e) { rows.push({ error: e.message, ms: performance.now() - t0 }); }
  await new Promise(r => setTimeout(r, Math.max(0, Number(spacing) - (performance.now() - t0))));
}
const ok = rows.filter(r => r.status === 200);
const changes = [];
for (let i = 1; i < ok.length; i++) if (ok[i].hash !== ok[i - 1].hash) changes.push(ok[i]);
const gaps = changes.slice(1).map((c, i) => +(c.header - changes[i].header).toFixed(1));
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
console.log(JSON.stringify({
  requests: rows.length, ok: ok.length, errors: rows.filter(r => r.error).length,
  fetchMs: { p50: Math.round(pct(ok.map(r => r.ms), 0.5)), p95: Math.round(pct(ok.map(r => r.ms), 0.95)) },
  bytes: { p50: pct(ok.map(r => r.bytes), 0.5) },
  contentChanges: changes.length,
  headerStepS: gaps,
  headerAgeAtFetchS: { p50: +pct(ok.map(r => r.at - r.header), 0.5).toFixed(1) },
}, null, 1));
