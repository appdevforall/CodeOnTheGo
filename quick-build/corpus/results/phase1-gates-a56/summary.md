# Phase 1 benchmark gates C1/C2/C3/C5 - A56 on-device results

- Date: 2026-07-17 (device clock GMT; 2026-07-16 evening PDT)
- Device: Samsung Galaxy A56 (SM-A566B, Android 16, serial RZGYC24640P), USB-powered, battery 79-80%
- Build: CoGo v8 debug from feature/ADFA-4128-quick-build (APK 0716-2140), test app com.demo.quickbuild
- Project: LemonadeStand (minimal single-module Kotlin app, real applicationId `com.demo`)
- Raw evidence: `raw/` (c1-runs.log, c2-standard-run.log, c5-rebaseline.log, c3-builds.log, c3-samples.log)

## C1 - per-edit-class hot-reload latency

buildDurationMillis from QuickBuildSessionManager logcat; warm session, LemonadeStand,
gens 12-82 (16 interactive edits + 55 soak edits; `raw/c1-runs.log`, `raw/c3-builds.log`):

| Edit class | n | p50 | p95 | min | max | Status |
|---|---|---|---|---|---|---|
| value-only string edit | 39 | **868ms** | **1116ms** | 704 | 1516 | PASS - every edit hot-reloaded |
| method-body edit | 32 | **862ms** | **943ms** | 758 | 1078 | PASS |
| resource (value) edit | 2 | - | - | - | - | **FAIL - product bug #1 below (logsender)** |
| wide changeset (const/inline) | - | - | - | - | - | covered by device matrix: const-change 21 files 1846ms, inline-body 11 files 1666ms (results/20260716T225056Z-device) |

All code edits combined: n=71, p50 866ms, p95 1066ms, max 1516ms. Wall time
edit-on-disk -> Deployed (includes watcher debounce + deploy + activity recreate) ran
~1.0-1.1s at p50 on the interactively timed edits. Every edit produced exactly one build
(watcher single-build invariant held across all 71).

Gate read: on this device+app, per-edit p95 is comfortably under 2s for both measured
code classes. This is one mid-spec device point; the pool extends when Tailscale lands
(C4). Resource-edit class is blocked by issue #1.

## C2 - vs standard Run (gate: >=5x)

Head-to-head on the same one-line value edit, same device (`raw/c2-standard-run.log`):

| Path | Time | Interaction |
|---|---|---|
| Quick Build (watcher-triggered) | p50 0.97s build / ~1.05-1.1s wall edit->deployed | zero taps, no reinstall, activity recreate only |
| Standard Run, build phase | 2.88 / 3.16 / 4.78s (median 3.16s) | one tap |
| Standard Run, machine total | ~4.2s (3.16 build + 0.56 install-commit + 0.37 launch + dialog plumbing) | THREE dialogs: installer confirm + Play Protect + CoGo launch prompt; app restarts cold |

Ratios (minimal project = standard path at its floor):
- Machine-only: 4.2 / 1.07 = **3.9x** - below the 5x gate on this smallest-possible app
- With fast (1s/dialog) human dialog handling: 7.2 / 1.07 = **6.7x** - passes
- Realistic user handling of 3 dialogs (3-5s each): **>=10x**

Verdict: **conditional pass.** The pure-machine ratio on a minimal app is 3.9x because
LemonadeStand's Gradle build is already at its ~3s floor; the standard path's fixed
interactive cost (3 dialogs, cold app restart, state loss) is what users feel, and any
larger app widens the compile-side gap (host matrix: fanout const-edit 768ms quick vs
multi-second Gradle; device matrix baselines 0.9-11s). Recommend re-running C2 on a
mid-size corpus app for an uncontested machine-only >=5x.

Findings along the way:
- Play Protect re-prompts on EVERY install while the app is unscanned - B1's "one-time
  popup" framing is optimistic on Play-Protect-enabled devices. Quick Build's zero-dialog
  reload loop is a bigger UX win than the plan text assumes.
- Stale `com.demo` from a 07-14 release-CoGo install failed with
  INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature mismatch) - environment issue, fixed by
  uninstall; worth remembering for device-pool prep.
- CoGo's Run button dead-clicks while the "Installation failed" banner is showing; taps
  produce no build request until the banner is dismissed (core CoGo quirk, not quick-build).

## C3 - memory + thermal over a ~28-min edit soak

Loop: one edit every 30s for 28 min (55 edits, alternating value/method), samples every
~5 min (`raw/c3-samples.log`). Battery 79% USB-powered the whole time.

- **Reload latency did NOT degrade**: first-10 mean 911ms -> last-10 mean 837ms (it
  improved as JITs warmed). 55/55 builds succeeded.
- **CoGo (host) PSS flat**: 354 -> 357 -> 357 -> 357 -> 357 -> 344 -> 333 MB.
- **Test app PSS flat**: 49 -> 55 -> 53 -> 53 -> 53 -> 53 -> 50 MB - 82 generations of
  InMemoryDexClassLoader payloads did not accumulate resident memory.
- **quickbuild-daemon RSS grew then plateaued**: 436 -> 491 -> 498 -> 525 -> 540 -> 536
  -> 537 MB (+23% over the session, flat for the last ~10 min). Likely JIT/code-cache
  warm-up; a multi-hour soak should confirm it is not a slow leak. 537MB resident is
  the biggest single quick-build cost - relevant to C4 low-spec.
- Other JVMs got trimmed by Android while idle (Gradle daemon 390 -> 215 MB, Kotlin
  daemon 401 -> 226 MB, tooling-api 99 -> 42 MB) - no pressure events.
- **Thermal: Status 0 (NONE) at every sample; no throttling.** Battery temp 24.9-27.6C
  across the window, AP cached at 34.5C. No thermal status change at any point.

## C5 - rebaseline (full-rebuild) latency (gate info: cost of the fallback path)

Trigger: append a comment to `app/build.gradle.kts` on-device; watcher routes
GRADLE_CONFIG_CHANGED -> Invalidated -> Provisioning (full setup build) -> Ready.
Measured InvalidationDetected -> ProvisioningSucceeded (`raw/c5-rebaseline.log`):

| Run | Latency | Notes |
|---|---|---|
| 1 | **17.03s** | first rebaseline of the session, from a failed (wedged) state, ~8 min idle before |
| 2 | **8.15s** | warm Gradle daemon; also recovered wedged resource state |
| 3 | **7.15s** | back-to-back warm |

Steady-state rebaseline is **~7-8s** on the minimal project (warm daemons); a cold-ish
first hit is ~17s. Each rebaseline was verified recovered by a follow-up code edit that
hot-reloaded (854-1007ms). For context the STATUS.md cold setup build was ~1 min - the
warm number makes the fallback routing (manifest/gradle/native/processor edits) quite
acceptable on small projects; needs re-measurement on mid/large corpus apps.

## Product issues found (need fixes / tickets)

1. **Resource edits fail on real CoGo projects (logsender manifest injection).**
   Any resource-file edit routes to relink, and aapt2 fails:
   `resource bool/logsender_enabled (aka com.demo.quickbuild:bool/logsender_enabled) not found`
   in the merged manifest (LogSenderPlugin injects `@bool/logsender_enabled` into the
   manifest; the hot-relink resource set doesn't include the logsender AAR's resources).
   Reproduced twice, including immediately post-rebaseline. Corpus apps' resource-value
   relinks PASS on-device (results/20260716T225056Z-device), so the daemon relink is fine;
   the CoGo-side session feeds relink a manifest whose resource closure it doesn't provide.
   Every debug project has logsender enabled => the C1 resource-edit class is BLOCKED
   product-wide until fixed. Never-stale held: failure surfaced, app kept last-good gen.
2. **Failed relink wedges the session until rebaseline.** After the resource failure, a
   subsequent pure code edit re-fails with the same relink error (dirty resource set never
   clears); the session stays at the last-good generation until a gradle-touch rebaseline.
   Correct by never-stale, but recovery UX is poor - relates to A1 (failure overlay) and
   B3 (restart session). A "clear/retry" that drops the failed resource delta, or routing
   a repeated-identical-relink-failure to auto-rebaseline, would unwedge without user
   gradle edits.

## On-device matrix runner

Not re-run (would contend with the C3 soak); the same device + daemon ran it earlier
today: `results/20260716T225056Z-device` - 23/23 pass across 6 apps, including the
wide-changeset class (const-change 21 files recompiled, 1846ms; inline-body 11 files,
1666ms) and resource-value relinks (257-403ms). Device/host median compile ratio 2.2-16.8x.

## Device residue / state at end

- Session healthy: Deployed(generation=82), zero failures outstanding.
- LemonadeStand residue from the recipes: Main.kt message marker now "LOOP55",
  textSize toggled to 30f, values.xml accent #FFF6C445 -> #FFF6C446 (absorbed by
  rebaseline), app/build.gradle.kts has three trailing `// c5-*` comments.
- `com.demo` reinstalled from this branch's debug build (the 07-14 release-signed
  install was removed to clear INSTALL_FAILED_UPDATE_INCOMPATIBLE).
- CodeOnTheGoProjects/ otherwise untouched; device never rebooted.
