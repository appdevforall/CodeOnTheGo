# `domain/telemetry/` - the measurement vocabulary

Pure-JVM types for measuring the live-reload loop: one timeline per edit and one sink to report it. No Android. `E2eTimeline` holds the four monotonic stamps that bound one generation's save-to-live loop, plus optional step timings, host spans, and build counts, and computes the per-stage and unaccounted deltas from them. `QuickBuildMetricsSink` is the port the app layer implements to record per-build statistics.

| File | Purpose |
| --- | --- |
| [`E2eTimeline.kt`](E2eTimeline.kt) | One generation's four-stamp timeline plus `StepTimings`, `HostSpans`, `BuildCounts`; derives stage deltas and the grep-stable log `format`/`parse`. |
| [`QuickBuildMetricsSink.kt`](QuickBuildMetricsSink.kt) | Interface for recording session/build/invalidation/reload/rebuild stats; must be cheap and never throw. Includes a `Noop` implementation. |
