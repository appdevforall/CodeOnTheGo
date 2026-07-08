# Making the edit→run loop significantly faster on slow devices (ADFA-4128)

## Where the time actually goes (measured, A56)

| Stage | Code edit | Resource edit | Scales on slow HW? |
|---|---|---|---|
| **compile** (kotlinc, warm) | **~350–550 ms** | — | **worst** — heaviest JVM workload |
| aapt2 link (warm subprocess) | — | **~175–220 ms** (cold 554) | moderate |
| d8 (incremental, 1 class) | ~15 ms | — | negligible |
| package apk | ~70 ms | ~70 ms | mild |
| deploy (on-device = local write) | ~5 ms | ~5 ms | negligible |
| shell reload + re-render | ~40–55 ms | ~40–55 ms | mild–moderate (complex UI re-inflation) |
| **loop total (on-device projection)** | **~500–650 ms** | **~250–350 ms** | |

**The plumbing I built (d8 + package + deploy + reload ≈ 130 ms) is already near its floor.**
On a code edit, **compile is ~75% of the loop**; on a slow device it's an even larger share,
because the Kotlin compiler is a big JVM program (parse + resolve + typecheck + IR + bytecode)
and weak cores + RAM pressure + thermal throttling hit it hardest. So "faster on slow devices"
= **attack the compiler**, not the loop mechanics.

Two separable walls: **kotlinc** (code path) and **aapt2** (resource path). Everything else is
already cheap.

## The tiered-loop architecture (the recommendation)

Don't run one loop for every edit. Diff the save and pick the cheapest tier that can express it.
Most iterations during active UI/logic work fall in Tier 0 or 1.

### Tier 0 — resource / UI edits: NO compiler at all
Layout, string, color, dimen, drawable changes need zero Kotlin compilation — just aapt2 link +
`ResourcesLoader` swap. **Measured ~250 ms, and it preserves running state.** This is the single
biggest free win because a large fraction of edit-debug-review iterations are visual tweaks.
- **Optimization: aapt2 daemon mode** (keep aapt2 warm, as AGP does) cuts the ~200 ms subprocess
  to tens of ms. Low effort, proven technique.
- Status: the loop already branches this way (the phase-2 devloop ran aapt2 only on res change);
  the compile service should too. **Proven** (numbers above).

### Tier 1 — method-body code edits: compile ONE class + JVMTI hot-swap
Instead of repackage → deploy → reload → re-render, compile only the changed class and
**redefine it in the running process** via ART's JVMTI (`art_ti`): attach a JVMTI agent to the
(already-debuggable) shell with `am attach-agent`, call `RedefineClasses` with the new dex.
- **Eliminates the repackage + deploy + reload + re-render tail** — and on a slow device,
  re-inflating a complex UI is often *more* than the 130 ms measured here.
- **Preserves running state** — no view rebuild, no re-navigating to the screen you're editing.
  On slow devices, *not losing your place* is as valuable as the raw ms.
- Still needs the one changed class compiled, so it doesn't beat the compiler wall — but it
  removes everything after it and keeps state.
- **Limitation (same as Android Studio Live Edit / Compose Hot Reload):** ART redefinition
  supports **method-body changes only** — no adding/removing methods/fields/classes, no signature
  changes. Structural edits fall back to Tier 2.
- Status: **proven-in-industry** (this is exactly Apply Changes / Live Edit / Compose Hot Reload);
  **proposed here** — prototypable on the debuggable shell, medium–high effort (native JVMTI agent).

### Tier 2 — structural changes: full compile + reload
Adding classes/methods, signature changes → the full path I built (~0.5–1 s+). Unavoidable for
structural edits, but they're the minority during iteration.

## Attacking the compiler wall itself (for Tier 1/2 on the slowest devices)

Ranked by leverage:

1. **Incremental Kotlin compilation with warm analysis caches.** The Kotlin compiler/daemon caches
   the analysis of unchanged files; a one-function edit re-analyzes very little. My spike
   recompiles the whole (one-file) payload each save — a production service using the incremental
   compiler + build cache would cut multi-file compile sharply and push single-function edits well
   under the whole-file number. **Biggest realistic "make kotlinc faster" lever.** Proposed, medium effort.

2. **Interpret the hot path — the original (non-Mini) Stubby.** Don't compile during editing at
   all: run the user's logic through an on-device interpreter (Kotlin-script / a JVM-bytecode or
   AST interpreter). This skips the kotlinc backend + d8 **entirely** — the edit loop becomes
   little more than "reload source," which is the real answer for the weakest hardware. This is
   precisely the interpreter path the ticket's **Mini**-Stubby deprioritized ("keeps the
   translation into DEX code, obviating the need for a JVM in the stub … slightly slower [runtime]
   but less technically challenging"). The trade the ticket made — DEX for less complexity + faster
   *runtime* — reverses when the priority is *iteration latency on slow devices*: an interpreter is
   a big build cost and a slower-running app, but it removes the compile wall that dominates the
   loop. Worth reopening explicitly for the slow-device target. Proposed, high effort.

3. **Lighter-language / Java hot path.** javac is ~4× faster than kotlinc warm on the A56
   (~100 ms vs ~400 ms). Not an option for Kotlin users, but relevant if a UI subset or a DSL
   drives the hot path. Proven ratio; niche applicability.

## The real slow-device constraint is often RAM/thermal, not CPU

On a low-end offline device the binding limit is frequently **memory**: a resident ~60 MB Kotlin
compiler JVM + d8 + the running app competing for 2–3 GB, plus thermal throttling that punishes
sustained compile. Design consequences:
- Cap the compile-service JVM heap; under memory pressure accept a cold single-shot compile rather
  than OOM-killing the app.
- **Prefer Tier 0 and Tier 1** — resource swap and class redefinition need far less RAM than a full
  compile, so they degrade gracefully on the weakest hardware where a full compile might not even fit.
- This is why the tiered design isn't just a latency optimization — on the slowest devices it may be
  the difference between a loop that runs and one that thrashes.

## Bottom line

- The loop plumbing is already minimal (~130 ms); don't optimize it further.
- **Biggest, cheapest win: Tier 0** — route resource/UI edits around the compiler entirely
  (measured ~250 ms, aapt2-daemon → tens of ms). A large share of iterations are visual.
- **Biggest perceived-latency win: Tier 1** — JVMTI class redefinition for method-body edits:
  skips the reload tail and preserves state. Industry-proven; the clear next prototype.
- **For the weakest hardware where even one-file compile is too slow:** reopen the **interpreter**
  path (original Stubby) — it trades runtime speed + build effort for near-zero compile latency.
- Respect RAM/thermal: the tiered design degrades gracefully; a monolithic full-compile loop doesn't.

Concretely, for CoGo's slow-offline audience I'd build: **tiered dispatch (Tier 0 resource path +
Tier 1 redefinition) on top of the persistent compile service**, and evaluate an interpreter hot
path as a separate track for the lowest-end devices. Tier 0 is a few days; Tier 1 is the next
prototype (JVMTI agent); the interpreter is a project.
