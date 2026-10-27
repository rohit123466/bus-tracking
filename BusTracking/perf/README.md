# Live-tracking performance checks

Scripts used to measure the live map end to end. None of them ship with the app; they need Node 18+,
and the browser ones use the frontend's `@playwright/test` with installed Microsoft Edge.

| Script | Measures |
|---|---|
| `probe-source.mjs` | How often the OTD VehiclePositions feed changes and how long a fetch takes (reads `DTC_API_KEY`; ~30 requests). |
| `perf-live.mjs <base> <seconds> [sampleMs]` | `/buses/regional` latency, wire size, server feed cadence, and how old each GPS fix is when the API first serves it. |
| `load.mjs <base> <clients> <pollMs> <seconds> [serverPid]` | Latency percentiles, wire MB/min and server CPU with many map clients polling at once. |
| `record-snapshots.mjs <base> <dir> [count]` | Saves consecutive real answers for `map-bench.mjs`. |
| `map-bench.mjs <frontendDir> <snapDir> <label> [seconds] [distDir]` | Replays recorded answers into a production build; counts Leaflet calls per update and main-thread time. |
| `real-check.mjs <frontendDir> <distDir> <label> <apiBase> [seconds] [--full]` | Against a real backend: delay from a fix reaching the server to the tab having it; with `--full` also mount/unmount leaks, a route filter and bus selection. |
| `jvm/IndexBench.java`, `jvm/IdentifierBench.java` | `TransitIndex.load()` time and retained heap; `RouteIdentifier.refresh()` time. Run with `java -cp <classes>;<deps> jvm/IndexBench.java <GTFS.zip> <runs>`. |

Typical sequence (backend on 8080 with the `demo` profile, see the main README):

```sh
node perf/perf-live.mjs http://localhost:8080 180 1000
node perf/load.mjs http://localhost:8080 20 2000 60 <java pid>
node perf/record-snapshots.mjs http://localhost:8080 perf/snapshots
cd frontend && npm run build && node ../perf/map-bench.mjs . ../perf/snapshots after 40
```

Compare two builds by running each on its own port with its own copy of `backend/data/` (H2 locks
its file), and by building the frontend into separate `--outDir`s.

## Results, 27 Oct 2026 (local, Windows, live OTD feed, 1,200–1,500 buses shown)

Measured against the code before this change on the same machine, feed and recorded data.

| | Before | After |
|---|---|---|
| `/buses/regional`, 1 client: p50 / p95 | 375 / 676 ms | 44 / 466 ms |
| `/buses/regional`, 20 clients every 2 s: p50 / p95 / p99 | 2,941 / 11,455 / 16,203 ms | 154 / 605 / 6,233 ms |
| …requests completed in 60 s (600 intended) | 266 | 540 |
| Wire size per poll | 692 KB | 62 KB (gzip, rounded numbers) |
| Age of a GPS fix when first served: p50 / p90 | 24.9 / 32.3 s | 22.1 / 28.1 s |
| Fix on server → in the browser tab: mean / p50 / p90 | 3.7 / 3.3 / 8.0 s | 2.0 / 0.6 / 4.7 s |
| Overview map, per update: main-thread / script time | 180 / 105 ms | 115 / 63 ms |
| Overview map, per update: `setLatLng` / `setStyle` / `on` | 3,886 / 3,897 / 3,920 | 986 / 886 / 34 |
| `TransitIndex.load()` (C1, SerialGC, 1 CPU): time / retained heap | 11.97 s / 71 MB | 10.31 s / 58 MB |
| `RouteIdentifier.refresh()` over 1,776 learned paths | 201 / 173 s | 91 / 84 s |
| Legacy publish thread, JFR samples per 60 s | 857 | 192 |

About 20 s of each fix's age comes before the feed is published (device → OTD) and cannot be
changed here. Street-level map updates (labelled pins) showed no measurable main-thread change.
