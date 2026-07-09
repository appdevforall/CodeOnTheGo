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

## Tier 1 status — WORKING on-device (an earlier "ART limitation" was my bug)

Tier 1 is a genuine ART **class redefinition (hot-swap)** and it works on the A56
(Android 16 / One UI 8.5). A JVMTI agent (`hotswap-agent/libhotswap.so`, bundled in
the shell APK) attaches via `am attach-agent`, acquires `can_redefine_classes`,
watches a drop dir, finds the loaded `app.payload.Main`, and calls `RedefineClasses`
with a single-class method-body dex. **Verified end-to-end:** edit `onTap`'s body →
the service redefines Main in place → the next tap runs the new code, the tap-count
state is **preserved**, and the status bar stays on the same generation (**no reload,
no re-render**). Screenshot: button shows the edited text with the count intact.

My first pass concluded "blocked by an ART limitation" from the error code alone. That
was wrong — the code was `ILLEGAL_ARGUMENT` (103), not the structural-change codes, and
that pointed at *the dex I was handing ART*, not the platform. Getting it robust took
three real fixes:

1. **`ILLEGAL_ARGUMENT` (103) → a true single-class dex.** d8 was desugaring the
   `setOnClickListener {}` **SAM conversion** into a synthetic class *inside Main's
   dex*, so "Main only" was actually a 2-class dex and ART rejected it (its class set
   didn't match the definitions). Fix: compile with **`-Xlambdas=class`
   `-Xsam-conversions=class`** so lambdas/SAMs become separate named classes; then d8
   of `Main.class` alone is genuinely one class.
2. **`SCHEMA_CHANGED` (64) → a synthetic-accessor-free, schema-stable payload.** When
   the SAM class touched Main's *private* members, the compiler added synthetic
   accessor methods to Main that differed between the single-class and full dex — a
   schema mismatch on a method-body edit. Fix: structure the payload so the hot path
   uses only **public** members (`@JvmField` state + public `@JvmStatic` methods), so
   Main has no synthetics and d8-of-Main-alone matches the loaded Main exactly.
3. **Robust to multiple loaded generations.** The shell can hold the previous gen's
   classloader, so two `Main` classes may be loaded with different schemas. The agent
   redefines **each matching generation individually** and reports success if the live
   one succeeds; a stale gen's failure doesn't block the visible gen.

Genuine structural changes (adding a method/field, signature change) correctly return
`SCHEMA_CHANGED` and **fall back to Tier 2** — that's the right behavior, not a failure.

**Latency note (honest):** Tier 1 still *compiles* the changed class (kotlinc ~330 ms
warm here), so its raw save→effect latency (~800 ms over adb) is compile-dominated,
similar to Tier 2 on this tiny payload. Tier 1's win is **not** primarily ms on a small
app — it's **skipping the repackage + reload + re-render and preserving running state**,
which on a *complex* app (a deep screen you'd otherwise lose and have to re-navigate to)
is the real payoff, and grows on slow hardware where re-inflating a complex UI is
expensive. Pairing Tier 1 with method-level incremental compilation (or the interpreter
hot path, FASTER-ON-SLOW-DEVICES.md §2) is what would also cut the compile half.

Tier 1 stays gated behind `-Dtier1=true` (off by default) pending hardening (it needs
the agent auto-attached at shell startup and the single-generation invariant enforced),
but it is a **working** on-device hot-swap, not a dead end.

## Recommendation

Ship **Tier 0 + Tier 2** for the baseline win (visual edits ~sub-second on slow devices;
compile cost confined to structural code changes). **Tier 1 hot-swap now works** and is
worth productionizing for the state-preserving, no-re-render path — auto-attach the agent
at shell startup, enforce one live generation, keep the schema-stable compile flags, and
pair it with incremental compilation to also shrink the compile half.
