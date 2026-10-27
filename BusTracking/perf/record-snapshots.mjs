// Saves consecutive real /buses/regional answers (one per feed update) for map-bench.mjs.
// usage: node record-snapshots.mjs <baseUrl> <outDir> [count]
import { mkdirSync, writeFileSync } from 'node:fs';
const [base = 'http://localhost:8080', dir = 'snapshots', count = '8'] = process.argv.slice(2);
mkdirSync(dir, { recursive: true });
let last = null, n = 0;
const end = Date.now() + 300000;
while (n < Number(count) && Date.now() < end) {
  const d = await (await fetch(`${base}/api/transit/buses/regional?region=ANY&routeNumber=`)).json();
  // New positions, not just a new fetch time (an unchanged feed still moves fetchedAt).
  const stamp = d.buses.reduce((s, b) => s + b.lastUpdatedEpochSeconds, 0);
  if (stamp !== last && d.buses.length > 0) {
    last = stamp;
    writeFileSync(`${dir}/snap-${String(n).padStart(2, '0')}.json`, JSON.stringify(d));
    console.log('saved', n++, d.buses.length, 'buses');
  }
  await new Promise(r => setTimeout(r, 2000));
}
