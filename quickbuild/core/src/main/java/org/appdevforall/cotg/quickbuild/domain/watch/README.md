# `domain/watch/` - what counts as a change

Turns the raw watcher event stream into clean, deduplicated build batches. Decides which filesystem events are relevant, debounces a burst of writes into one batch, and reconciles paths that vanished between the event and the build. Pure JVM (a coroutine clock, unit-tested with virtual time); no Android.

| File | Purpose |
| --- | --- |
| [`WatchFilter.kt`](WatchFilter.kt) | Decides if an event is relevant: under a watched `src/`/`res/`/`assets/` root or a watched Gradle file, not a `build/` intermediate, not a recognized-shape temp file. |
| [`ChangeCoalescing.kt`](ChangeCoalescing.kt) | Defines `WatchEvent` (Modified/Removed) and `coalesceChanges`, which debounces events into batches (quiet timer plus a hard cap from the first event), last-event-per-path wins. |
| [`WatcherBatchReconciler.kt`](WatcherBatchReconciler.kt) | Splits a coalesced batch into files that still exist, deletions, and noise; a modified-but-gone path with a recognized shape becomes a removal, otherwise it is dropped as a rename-tool temp. |
