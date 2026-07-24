# Relink resource-table poisoning — forensic notes (ADFA-4128)

Detail behind the README "Known limitations" entry *"a successful relink can still crash
the app and poison the session"*. The user-visible summary lives in the README; the
mechanism and the identified (unbuilt) fix live here so the README can stay short.

## Symptom

A resource reload that aapt2 reports as a SUCCESSFUL relink still crashes the test app on
the next `recreate()` — sometimes on an edit that touched no resource at all (a pure code
edit) — with `ClassNotFoundException: android.view.adaptive-icon` while inflating the
launcher icon. Once it happens, every subsequent reload repeats the crash until the
session is reset.

## Two independent triggers, same poisoning symptom

1. **Original (fixed):** the old arsc-only relink dropped every file-backed resource. Fixed
   by shipping the FULL relinked resource apk (resources.arsc plus every compiled resource
   file), not a bare extracted table. See README "Test-app architecture".
2. **Second, pre-existing (confirmed NOT fixed), device-verified 2026-07-23
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

## Identified fix (not built)

- **Trigger-specific:** `aapt2 link --stable-ids` fed from AGP's own
  `stable_resource_ids_file` build artifact (produced for free by every setup build) would
  pin relink IDs to the baseline's, eliminating this whole class of trigger. Wiring needs a
  new setup-build-reported field + daemon protocol field + plumbing across gradle-plugin
  (`QuickBuildJson`/`QuickBuildTasks`), `QuickBuildProjectLayout`, `DaemonProtocol`'s
  `RelinkRequest`, and `Aapt2Link` — a follow-up ticket, not a quick patch.
- **General safety net (trigger-independent):** treat a crash during a pending reload as
  reason to distrust the just-applied resource generation and fall back to the last
  known-good one. Real recovery machinery; not built here.
