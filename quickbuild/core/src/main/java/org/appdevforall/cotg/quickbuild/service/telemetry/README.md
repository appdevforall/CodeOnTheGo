# `service/telemetry/` - stamping and reporting a build's timeline

This folder is the service-side counterpart to `domain/telemetry`: it stamps a timeline as a single build runs the pipeline and guards the reporting of it. One recorder collects per-step spans and counts and mints the finished `E2eTimeline`; a shared helper runs every metrics call so a misbehaving sink can never fail a build.

| File | Purpose |
| --- | --- |
| [`E2eTimelineRecorder.kt`](E2eTimelineRecorder.kt) | Collects one build's timings (scan, compile, policy, dex, relink spans plus the daemon's own step breakdowns and source/class counts) as it moves through the pipeline, then builds the `E2eTimeline`. |
| [`MetricsReporting.kt`](MetricsReporting.kt) | `report {}` helper that runs a metrics call and swallows any failure into a logged warning, so metrics can never break a build. |
