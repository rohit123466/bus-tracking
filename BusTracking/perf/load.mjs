// Concurrent clients polling /buses/regional, as many open Live Maps would.
// usage: node load.mjs <baseUrl> <clients> <pollMs> <seconds> <serverPid>
import { execSync } from 'node:child_process';
const [base, clients = '20', pollMs = '2000', seconds = '60', pid] = process.argv.slice(2);
const url = `${base}/api/transit/buses/regional?region=ANY&routeNumber=`;
const cpuSeconds = () => pid ? Number(execSync(`powershell.exe -NoProfile -Command "(Get-Process -Id ${pid}).TotalProcessorTime.TotalSeconds"`).toString().trim()) : NaN;
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };

const lat = [], errors = [];
let bytes = 0;
const end = Date.now() + Number(seconds) * 1000;
const cpu0 = cpuSeconds();
async function client(i) {
  await new Promise(r => setTimeout(r, (Number(pollMs) / Number(clients)) * i)); // spread start
  while (Date.now() < end) {
    const t0 = performance.now();
    try {
      const res = await fetch(url, { headers: { 'accept-encoding': 'gzip' } });
      const buf = await res.arrayBuffer();
      bytes += Number(res.headers.get('content-length')) || buf.byteLength;
      if (!res.ok) errors.push(res.status);
    } catch (e) { errors.push(e.message); }
    const ms = performance.now() - t0;
    lat.push(ms);
    await new Promise(r => setTimeout(r, Math.max(0, Number(pollMs) - ms)));
  }
}
await Promise.all(Array.from({ length: Number(clients) }, (_, i) => client(i)));
const cpu1 = cpuSeconds();
console.log(JSON.stringify({
  base, clients: Number(clients), pollMs: Number(pollMs), seconds: Number(seconds), requests: lat.length, errors: errors.length,
  latencyMs: { p50: Math.round(pct(lat, 0.5)), p95: Math.round(pct(lat, 0.95)), p99: Math.round(pct(lat, 0.99)), max: Math.round(Math.max(...lat)) },
  wireMBPerMin: +(bytes / 1048576 / (Number(seconds) / 60)).toFixed(1),
  serverCpuSecondsPerMin: +((cpu1 - cpu0) / (Number(seconds) / 60)).toFixed(1),
}));
