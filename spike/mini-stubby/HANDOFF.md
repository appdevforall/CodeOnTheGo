# ADFA-4128 handoff — resume here to plan the real feature build

Written 2026-07-14, end of the prototyping spike. Next step (in ~2 days, after team
discussion): make the plan for the actual feature. The likely shape: **build from scratch,
using this repo as reference**, in the direction of the
[Discussion Doc](https://appdevforall.atlassian.net/wiki/spaces/SD/pages/900038699)
— plus three things the prototype deliberately skipped:

1. **Validation corpus** — verify all supported edit operations against a broad corpus of
   real apps, not one demo app. (The spike tested androidx/Material3/Compose/Fragments/
   native/multi-Activity once each; see CAPABILITY-MATRIX.md.)
2. **Normal build vs live-reload build** — design how the two coexist and integrate
   (when does a live-reload session start/stop, what invalidates it, how does a full
   Gradle build hand state back). The spike ignored this entirely.
3. **UI polish** — the banner/status surface is prototype-grade (and the stuck-"Compiling…"
   failure mode is a known bug: it only clears on successful render, so a compile error or
   render crash leaves it stuck with stale content).

## Where everything is

- **Branch:** `ADFA-4128-prototype` on appdevforall/CodeOnTheGo (46+ commits, rebased on stage).
- **Team-facing writeups (primary copies, Confluence):**
  [Transcript Review](https://appdevforall.atlassian.net/wiki/spaces/SD/pages/900038660) (how we worked, per-stage learnings) and the
  [Discussion Doc](https://appdevforall.atlassian.net/wiki/spaces/SD/pages/900038699) (the technical proposal — the plan's starting point).
  Demo recordings are attached there; local copies are gitignored under `demo/recordings/`.
- **Code map:** README.md "Directory map" + "Bring-up: on-device live reload (step-by-step)".
- **Process retro:** RETRO-2026-07-14.md — read before planning; its lessons (verify behavior
  not green builds, terse docs, the on-device-only invariant) should shape how the real build runs.

## State of the prototype (honest)

Verified on the A56: full on-device loop, edit in CoGo editor → save → watch → compile →
dex → deploy → hot reload, ~2.5 s save→rendered (build 0.5–0.9 s warm incremental + reload
~30–110 ms). Both flows demoed and recorded (Flow 1 Ask-Claude, Flow 2 CoGo edit).

Known gaps a fresh puller hits (also in README):
- The **verified** config is the daemon's own file-watcher (`run_daemon_watch.sh`,
  `-Dwatch=true`, hand-patched on the device). The **staged default** (`-Dwatch=false` +
  CoGo `LiveReloadManager` FileObserver → POST /build) was never re-verified end-to-end on
  a clean device — and `ensureStarted` doesn't respawn a crashed daemon.
- Payload contract is narrow: `object Main { @JvmStatic fun render(host: Activity): View }`
  + `.livereload` marker. Real apps need the manifest/multi-Activity story from
  MULTI-ACTIVITY-OPTIONS.md.
- Tier-1 JVMTI hot-swap works but doesn't trigger re-render; Mac-harness only. On-device is
  Tier 0 (resources) / Tier 2 (compile+dex).

Key numbers for the plan (bench/, RESULTS-*.md): incremental Kotlin compile stays flat
~0.5 s regardless of app size; **dex scales with size** (423→1116 ms for 20→200 classes) —
per-change dexing matters at scale; full non-incremental compile balloons with app size.

## Device state (A56, RZGYC24640P)

CoGo debug build from this branch installed; `files/mstc/` holds the staged toolchain with
the hand-patched watch=true `run_daemon.sh`; `LemonadeStand` project has the `.livereload`
marker and the demo payload. Re-staging from scratch: `tools/ondevice/stage_ondevice.sh`
(writes the watch=false default).

## First questions for the planning session

- Which edit operations are officially "supported" for live reload (the validation-corpus
  contract), and what falls back to a normal build?
- Session model: how a live-reload session starts/stops and hands off to/from a full
  Gradle build without stale state.
- Shell-app vs in-place reload of the user's real app (manifest/permissions/multi-Activity
  — see MULTI-ACTIVITY-OPTIONS.md for the option space).
- Error UX: compile failures and payload crashes must surface honestly (fix the stuck
  banner by design, not patch).
