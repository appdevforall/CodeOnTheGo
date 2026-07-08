# Tiered edit→run loop — implementation, decision, and impact (ADFA-4128)

Implements the 3-tier design from FASTER-ON-SLOW-DEVICES.md in the persistent
compile service. The service diffs each save and dispatches to the cheapest tier
that can express the change.

## What was built

- **Tier dispatch** (`KotlinCompileService.watchLoop`): watches `src/` and `res/`
  recursively; a resource-only change → Tier 0, a `.kt` change → the code path.
- **Tier 0** (`resourceOnlyChange`): aapt2 relink + reuse the cached app/stdlib
  dex, repackage, deploy. **No compiler.**
- **Tier 1** (`tryTier1Redefine` + `hotswap-agent/`): compile only Main → a
  single-class dex → hand to a JVMTI agent attached to the shell, which calls ART
  `RedefineClasses`. On rejection → falls back to Tier 2.
- **Tier 2** (`buildAndDeploy`): full warm compile + repackage + reload (the
  baseline from the compile-service work).

## Measured on the A56 (Android 16 / One UI 8.5), over adb

| Tier | What changed | Total (adb) | Dominant cost | On-device projection* |
|---|---|---|---|---|
| **0** resource | color/string/layout | **~590–820 ms** | aapt2 ~160–270, pack ~110–260, **adb push ~280–320** | **~300–400 ms** (aapt2-daemon → ~150 ms) |
| **2** code | Kotlin method/body | **~670–1380 ms** | **kotlinc ~310–760**, d8 ~25–70, pack ~70–200, push ~250–350 | **~450–900 ms** |
| **1** hot-swap | method body only | *(intended: compile-1-class + redefine, no reload)* | — | *blocked on this ART build — see below* |

*On-device the `adb push` deploy (~280 ms) becomes a local file write (single-digit
ms), so subtract it and add nothing. That's the projection column.

**The decision this measures out:** Tier 0 skips the entire kotlinc cost (~310–760 ms
on the A56). A resource edit costs *less than half* a code edit even on this
moderately-fast device, and preserves the running payload's logical state.

## The A56-vs-slower-device impact (the key question)

The three stages scale very differently as hardware weakens:

| Stage | On A56 (Cortex-A720) | On a ~2–3× slower low-end device |
|---|---|---|
| **kotlinc** (Tier 2 only) | ~310–760 ms | **~1–2.5 s** — scales worst: heavy JVM workload, JIT warmup, hit hardest by weak cores + RAM pressure + thermal throttling |
| aapt2 (Tier 0) | ~160–270 ms | ~400–700 ms — scales ~linearly, no JVM |
| d8 (incremental, 1 class) | ~25–70 ms | ~60–150 ms — tiny |
| shell reload + re-render | ~40–55 ms | ~100–200 ms — mild |

So the impact of tiering **grows** as devices get slower:

- **Tier 0 (resource/UI edits) stays usable on slow hardware** — ~0.5–1 s, because
  it never runs the compiler. On the A56 it's ~2× faster than a code edit; on a
  slow device it's **3–5× faster**, because the thing it skips (kotlinc) is exactly
  the stage that blows up on weak hardware.
- **Tier 2 (structural code edits) degrades to ~1.5–3 s on slow devices** — the
  kotlinc wall. Unavoidable for structural changes, but they're the minority during
  iteration.
- **Net:** on the A56 the loop is comfortable in all tiers (~0.3–0.9 s). On a slow
  device, tiering is what keeps the *common* case (visual/UI tweaks — the majority
  of edit-debug-review iterations) at ~sub-second while only structural code changes
  pay the multi-second compile. Without tiering, every edit — including changing a
  color — would pay the full multi-second compile on slow hardware.

RAM angle (also from FASTER-ON-SLOW-DEVICES.md): Tier 0 needs no compiler JVM
resident for the edit, so on RAM-starved low-end devices it degrades gracefully where
a monolithic full-compile loop can thrash. This makes tiering not just a latency
optimization but, on the weakest hardware, a robustness one.

## Tier 1 status — implemented, blocked by an ART limitation on this build (honest)

The full Tier 1 pipeline works end-to-end **except** the final redefine:
- The JVMTI agent (`hotswap-agent/libhotswap.so`, bundled in the shell APK) attaches
  via `am attach-agent`, acquires `can_redefine_classes`, spawns a watcher, finds the
  exact loaded `app.payload.Main`, and calls `RedefineClasses` with a single-class,
  method-body-only dex.
- On Android 16 / One UI 8.5 (A56), `RedefineClasses` returns **`JVMTI_ERROR_ILLEGAL_ARGUMENT`
  (103)**, and `can_redefine_any_class` is **not offered** in the potential
  capabilities. Generic `am attach-agent` JVMTI redefinition of *app* classes is not
  fully supported on this build.
- This is exactly the ART-version fragility the multi-Activity research flagged for
  on-device class redefinition. Android Studio's Apply Changes / Live Edit achieve it
  via a **platform-internal ART deploy agent**, not a generic attached JVMTI agent —
  that path (or a userdebug/rooted ART, or a future ART that exposes the capability)
  is what a production Tier 1 would need.
- **The tiered system is robust regardless:** Tier 1 rejection falls back cleanly to
  Tier 2, so the loop always works. Tier 1 is gated behind `-Dtier1=true` (off by
  default) so the wasted compile-then-fallback isn't paid in normal operation.

Verdict on Tier 1: the *design* is sound and the *plumbing* is complete and proven;
the *ART capability* to land it via a generic agent isn't present on this device.
Treat Tier 1 as "ready pending an ART redefinition path," not as a dead end — its
value (skip reload + preserve state) is real, and the interpreter path
(FASTER-ON-SLOW-DEVICES.md §2) is the alternative that removes the compile entirely
for the slowest hardware.

## Recommendation

Ship **Tier 0 + Tier 2** now — that alone keeps visual edits ~sub-second on slow
devices and confines the compile cost to structural code changes. Pursue Tier 1 via
the ART deploy-agent path (not generic JVMTI) or evaluate the interpreter hot path
for the lowest-end target, per the slow-device analysis.
