# DEX (d8) time vs app size — results (2026-07-10)

Follow-up to a review question: *"add DEX time on a variety of file sizes."* The incremental
benchmark (`INCREMENTAL-RESULTS.md`) measured `kotlinc` only; this measures the **d8 / dexing**
half of a reload across the same synthetic app-size ladder. Same generator as `IncBench` (one
class per file, ~25 methods/class), so it's apples-to-apples with the compile ladder.

## Headline: whole-app dex grows with size; incremental (per-class) dex is flat

Mac, D8 (`com.android.tools.r8.D8`) under JBR-21, best-of-3, `--release --min-api 30`:

| App size | Classes | Whole-app dex | Single-class (incremental) dex | classes.dex size |
|---|---|---|---|---|
| 600 LoC | 20 | 423 ms | 383 ms | 10.7 KB |
| 3,000 LoC | 50 | 506 ms | 414 ms | 36 KB |
| 15,000 LoC | 100 | 790 ms | 424 ms | 138 KB |
| 30,000 LoC | 200 | **1,116 ms** | **415 ms** | 281 KB |

- **Each D8 invocation here carries ~380–420 ms of JVM + D8 startup** — visible as the flat
  single-class column (dexing *one* changed class is trivial; the time is almost all startup).
  The **warm in-process D8 API** the service actually uses pays this **once per session**, not per
  reload — so the real per-reload single-class dex is well under ~100 ms.
- **Whole-app dex is the size-dependent cost.** Subtracting the ~400 ms startup baseline, the
  actual dexing work grows ~linearly with class/method count: ~20 ms at 600 LoC → **~700 ms at
  30k LoC** (200 classes). Re-dex the whole app every keystroke and dex becomes a *second*
  size-scaling bottleneck sitting next to full compile.
- **Incremental dex stays flat** because it dexes only the changed class(es) with
  `--intermediate` (per-class DEX), to be merged into the app's existing dex set — the same
  shape as incremental compile.

## Design implication: the loop needs incremental *dex*, not just incremental *compile*

`INCREMENTAL-RESULTS.md` showed incremental **kotlinc** keeps the compile step flat regardless of
app size. This ladder shows the **dex** step has the identical problem and the identical fix:

| Reload strategy | Compile | Dex | Stays <1s at 30k LoC? |
|---|---|---|---|
| Naive (whole app) | grows (~9.6 s on A56) | grows (~1.1 s Mac → ~3–5 s A56) | ❌ |
| Incremental compile + **whole-app** dex | flat (~0.5 s) | grows | 🟠 dex dominates at scale |
| Incremental compile + **incremental** dex | flat (~0.5 s) | flat (<0.1 s warm) | ✅ |

So the fast loop's dex step must dex **only the changed classes** and merge, mirroring the
compile step. ART's per-class DEX + the D8 merge path (or replacing the changed `.dex` in a
multidex payload) both support this.

## Caveats / what's not yet measured

- **Mac numbers, not on-device.** The existing on-device point (the ~600-LoC lemonade game on the
  A56) dexed in ~200–400 ms warm; expect the same ~3–5× device multiplier seen for `kotlinc`, so
  a *whole-app* dex of a 30k-LoC app on the A56 would be ~3–5 s (🔴), and *incremental* dex would
  stay in the ~100–300 ms band (🟢). An on-device dex ladder is the natural follow-up.
- **Synthetic class density is low** (1 class/file → 20–200 classes for 600–30k LoC). Real Kotlin
  emits more classes per KLoC (data classes, lambdas, `$` synthetics), so whole-app dex on a real
  30k app would be *higher* than 1.1 s — which only strengthens the "incremental dex required"
  conclusion. The scaling *shape* is what transfers.

Harness: `compile-service/incremental/` (IncBench compile output) + `scratchpad/dexbench/timeit.py`.
