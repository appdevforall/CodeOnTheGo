# Relink resource-table poisoning — forensic notes (ADFA-4128)

Detail behind the README "Known limitations" entry *"a crashing reload has no
self-healing"*. The user-visible summary lives in the README; the mechanism and fix
history live here so the README can stay short. Both triggers below are FIXED; the
crash-recovery gap (the safety net) is the part still open.

## Symptom

A resource reload that aapt2 reports as a SUCCESSFUL relink still crashes the proxy app on
the next `recreate()` — sometimes on an edit that touched no resource at all (a pure code
edit) — with `ClassNotFoundException: android.view.adaptive-icon` while inflating the
launcher icon. Once it happens, every subsequent reload repeats the crash until the
session is reset.

## Two independent triggers, same poisoning symptom

1. **Original (fixed):** the old arsc-only relink dropped every file-backed resource. Fixed
   by shipping the FULL relinked resource apk (resources.arsc plus every compiled resource
   file), not a bare extracted table. See README "Proxy-app architecture".
2. **Second, pre-existing (FIXED via `--stable-ids`, below), trigger device-verified 2026-07-23
   (`corpus/results/20260723T111950Z-bug5-verify/` in the benchmark repo):** quick-build's
   relink feeds aapt2 only the project's own `src/main/res`, never the
   build-generated/injected resources the real Gradle build merges in (e.g. CoGo's
   LogSenderPlugin's `bool/logsender_enabled`). Dropping a whole resource TYPE shifts
   aapt2's canonical type-index assignment for every type ordered after it. The
   baseline-compiled manifest's fixed `android:icon` resource ID then resolves to the WRONG
   type against the freshly-relinked table, crashing icon inflation on the next
   `recreate()` — including one triggered by a pure code-only edit.

## Why it persists across reloads (crash-recovery gap)

`handlePayload` persists a resource payload to disk BEFORE applying it. A crash during the
activity recreate that follows happens OUTSIDE `handlePayload`'s own try/catch — the
recreate's `onCreate` runs on a later main-thread frame, caught only by the process's
uncaught-exception handler. So `failReload`'s rollback never runs, and the
already-persisted table is reapplied at the next process boot, repeating the same crash on
every reload until the session is reset.

## Fix (built 2026-07-24; kept for mechanism history)

- **Trigger-specific (BUILT):** `aapt2 link --stable-ids` fed from AGP's own
  `stable_resource_ids_file` build artifact (produced for free by every proxy app build) pins
  relink IDs to the baseline's, eliminating this whole class of trigger. Wired end-to-end:
  gradle-plugin reports the file (`QuickBuildJson`), `QuickBuildProjectLayout.stableIdsFile()`
  carries it, `DaemonProtocol`'s `RelinkRequest.stableIds` transports it, and `Aapt2Link`
  emits the flag. Library resources are additionally overlaid via
  `RelinkRequest.libraryResources` (the proxy app build's `.flat` units).
- **General safety net (trigger-independent, NOT built):** treat a crash during a pending
  reload as reason to distrust the just-applied resource generation and fall back to the
  last known-good one. Real recovery machinery; still open — this is the remaining gap the
  README entry tracks.
