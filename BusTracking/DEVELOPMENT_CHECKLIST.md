# Completion checklist — 2026-10-13

Baseline: main aa7e3eaf (merged PR #21); PR #19 preserves the vertical seat layout.

- [x] Clone latest main, review PRs #19/#21 and existing verification documents.
- [x] Inspect live index, realtime ingestion, ETA, alternatives, walking, homepage and deployment configuration.
- [x] Validate service-instance, route, direction and stop identifiers; stopped-speed behavior and stale cancellations.
- [x] Complete arrival position/direction and alternative walking-distance/time-saved presentation.
- [x] Validate pedestrian provider output and frontend freshness handling.
- [x] Verify replaceable static feed, shape ingestion, and safe legacy import recovery.
- [x] Run Java 21 Maven clean verify and frontend install/tests/lint/build.
- [x] Run actual demo backend/frontend and browser automation, including accessibility/ticketing/admin.
- [x] Check official DTC feed availability and document external limitations without invented predictions.
- [x] Review Render, health, CORS, WebSocket, secrets and restart behavior.
- [ ] Write FINAL_VERIFICATION.md with executed results, commit, push and open PR to main.

Existing implementations to preserve: exact public-route matching; both route directions; nonoverlapping polling; GTFS shapes and absolute TripUpdates; conservative direct/one-transfer comparisons; GPS watcher and recenter; ORS fallback; vertical wheelchair/seat layout; ticketing and protected admin APIs.
