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

## ON-DEVICE ladder (Samsung A56) — the numbers that decide "how to DEX"

Ran the same class sets through an **in-process D8** harness on the A56 (warm — one JVM, `D8.run()`
called repeatedly, exactly like the compile service), via CoGo's bundled JDK 21. Two axes plus a
cross-check. Harness: `scratchpad/dexbench/OnDeviceDexBench.java`.

**Warm-up cost:** cold first dex = **1,440 ms**, warm (settled) 1-class dex = **36 ms** → ~1.4 s of
JVM/D8 startup, paid **once per session**. Every per-edit number below is warm.

**Axis A — whole-app dex grows with app size** (best-of-3):

| App size | Classes | ~Methods | Whole-app dex (A56) |
|---|---|---|---|
| 600 LoC | 20 | 100 | 266 ms |
| 3,000 LoC | 50 | 500 | 358 ms |
| 15,000 LoC | 100 | 2,500 | 857 ms |
| 30,000 LoC | 200 | 5,000 | **917 ms** |

**Axis B — incremental dex tracks *change size* (methods dexed), not app size** (in the 30k app, best-of-3):

| Classes changed | ~Methods | Dex (A56) |
|---|---|---|
| 1 | 25 | **36 ms** |
| 5 | 125 | 81 ms |
| 20 | 500 | 130 ms |
| 50 | 1,250 | 293 ms |
| 100 | 2,500 | 470 ms |
| 200 (= whole app) | 5,000 | 567 ms |

**Cross-check — the decisive one:** dexing **5 classes** costs **44 ms in the 3k-LoC app vs 48 ms in
the 30k-LoC app** — essentially identical. **Incremental dex is independent of total app size**; it
depends only on how many methods you re-dex.

So on real hardware: a typical **1-file edit dexes in ~36 ms warm regardless of app size**, while
re-dexing the *whole* 30k app is ~0.9 s (and climbs with method density on a real app). Dex is not
the bottleneck *if* you dex incrementally; it becomes one if you don't.

## Design implication: the loop needs incremental *dex*, not just incremental *compile*

`INCREMENTAL-RESULTS.md` showed incremental **kotlinc** keeps the compile step flat regardless of
app size. This ladder shows the **dex** step has the identical problem and the identical fix:

| Reload strategy | Compile (A56) | Dex (A56) | Stays <1s at 30k LoC? |
|---|---|---|---|
| Naive (whole app) | grows (~9.6 s) | grows (~0.9 s, higher on dense apps) | ❌ |
| Incremental compile + **whole-app** dex | flat (~0.5 s) | grows (~0.9 s) | 🟠 dex dominates at scale |
| Incremental compile + **incremental** dex | flat (~0.5 s) | flat (**~36 ms warm**) | ✅ |

So the fast loop's dex step must dex **only the changed classes** and merge, mirroring the
compile step. ART's per-class DEX + the D8 merge path (or replacing the changed `.dex` in a
multidex payload) both support this. On the A56 that makes the *whole* reload
(incremental compile ~0.5 s + incremental dex ~0.04 s + package/deploy ~0.4 s + load ~0.04 s)
land near **~1 s even for a 30k-LoC app** — the number that keeps the loop honest at scale.

## Caveats / what's not yet measured

- **Synthetic class density is low** (1 class/file → 20–200 classes for 600–30k LoC). Real Kotlin
  emits more classes per KLoC (data classes, lambdas, `$` synthetics), so *whole-app* dex on a
  real 30k app would be *higher* than the measured ~0.9 s — which only strengthens the
  "incremental dex required" conclusion. Incremental dex tracks *methods changed*, so it's far less
  sensitive to this. The scaling *shape* is what transfers.
- **Change size = methods, not files.** Axis B is indexed by classes for convenience; the real
  driver is method/instruction count in the changed classes (see the ~Methods column). A one-line
  body edit re-dexes one class (~36 ms); a change touching many classes costs proportionally more,
  but a single-file edit — the common case — stays at the bottom of the ladder.

Harness: Mac — `compile-service/incremental/dex/timeit.py`; on-device — `compile-service/incremental/dex/OnDeviceDexBench.java`
(in-process warm D8), run under CoGo's bundled JDK on the A56 (`/data/local/tmp/mstc`). Class inputs
come from `IncBench`'s compiled output at each size.
