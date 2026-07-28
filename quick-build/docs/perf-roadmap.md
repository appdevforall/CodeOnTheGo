# Quick Build performance roadmap

Status: sequenced plan, nothing here is committed. This supersedes the
per-task performance notes scattered across the branch — read this first, and
treat [`sora-slow-path-gap.md`](sora-slow-path-gap.md) (root cause + method)
and [`incremental-javac-design.md`](incremental-javac-design.md) (javac detail)
as its appendices. `incremental-javac-design.md` is superseded on framing only;
its options and test plan stand.

Provenance is mandatory. `[measured on a56]` = Samsung A56;
`[measured on c107]` = the 1.9 GB-class low-spec device; `[measured on host]` =
Mac Mini; `[inferred]` and `[unmeasured]` mean what they say. Untagged prose is
code reading.

Evidence: `quick-build/corpus/results/20260728T172912Z-sora-deepdive/`
(per-step tables, both variants' logcat, the filesystem microbenchmark). A56,
CoGo `C-d-0728-1026` / `C-d-0728-1044` from `5d2d94a78` plus scratch
instrumentation. The measured device rows are n=1 for variant A and n=2 for
variant B; they are effects far outside the visible spread, not distributions.

## Where a warm edit goes today

The reference workload is `sora-editor-full` (292 sources: 218 `.java` +
74 `.kt`, producing 464 class files / 1.46 MB of bytecode) — the corpus's
worst Quick Build case and the only one where Quick Build lost to a standard
build. Warm edit, ms `[measured on a56]`:

| edit | total | javac | kotlinc | strip | d8 | policy+walks |
|---|---|---|---|---|---|---|
| Java body | 14718 | 3983 | 659 | 5492 | 3104 | 883 |
| Kotlin body | 14922 | 2849 | 3447 | 4659 | 2421 | 1058 |
| Java ABI change | 28055 | 2677 | 16377 | 4776 | 2268 | 1465 |

Two things to read off it. First, the four shipped analytics fields
(`kotlinMs`, `javacMs`, `stripMs`, `d8Ms`) sum to about half the edit — the
dominant cost was in the unmeasured half, which is how a careful design note
reached the wrong conclusion from real device data. Second, no single compiler
is the bottleneck: the largest single line on a Java body edit is the ACC_FINAL
**strip pass**, which does no compilation at all.

For context, Quick Build is a median **2.5x** faster than an incremental
standard build across 21 corpus apps on the same device `[measured on a56]`.
None of the work below is a v1 blocker; it widens the set of projects Quick
Build helps and closes the cases where it loses.

## The sequence

Ordered by measured payoff per unit of risk. Items 1-3 compound; item 4 moves
only one edit class.

### 1. Move the daemon scratch tree off emulated storage

**Payoff: -45% on every warm edit measured** `[measured on a56]` — sora
14.7 s -> 8.1 s, `medium-kotlin` (28 sources) 2.5 s -> 1.4 s. Against the
11.9 s standard incremental build, sora's Java body edit goes from 0.81x
(losing) to 1.47x (winning). Note the small app gains the same 45%: this is not
a large-project fix.

**Why.** `QuickBuildSessionManager.kt:674` sets
`outDir = File(layout.projectRoot, ".androidide/quickbuild/out")`, and
`layout.projectRoot` is under `/storage/emulated/0/CodeOnTheGoProjects/` —
FUSE-backed emulated storage, where every `open`/`write`/`mkdir` is a userspace
round trip. Same 464 files / 1.46 MB / 40 dirs, `cp -r`, best of 3: **192 ms**
on `/data/local/tmp` vs **9985 ms** on `/storage/emulated/0`, a **52x** per-file
toll `[measured on a56]`. Mechanism, and where every other CoGo component
stores its data, in [`docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md).

The A/B is what makes this a filesystem finding rather than a general speedup —
only per-file steps moved `[measured on a56]`:

| step (sora) | emulated | app-private | change |
|---|---|---|---|
| strip | 4659-5492 | 168-332 | ~20x |
| deploy policy | 615-1228 | 64-111 | ~10x |
| output-tree walks | 237-268 | 26-54 | ~6x |
| kotlinc (1 file) | 3447 | 3264-3352 | noise |
| d8 | 2421 | 2128-2478 | noise |

**Effort: S. Risk: M.** The measuring patch was 8 lines. The risk is not in the
move, it is in everything that assumed the out dir lives under the project.

**What must be true first.**
- A cleanup policy for stale per-project work dirs. On the project side they
  died with the project folder; in app-private storage they accumulate forever,
  which matters most on the 1.5 GB tier.
- An audit of the rebaseline and teardown paths for code that assumes the out
  dir sits under the project root.
- A collision-safe directory key. The experimental patch keyed on `hashCode()`
  of the project path — fine to measure with, not to ship.

**The test that proves it.** A device A/B on the same build: warm
`01-java-body-edit` on `sora-editor-full` and on `medium-kotlin`, expecting
~-45% on both, with `stripMs` collapsing ~20x and `kotlinMs`/`d8Ms` flat. Flat
compute steps are the control — if they move too, something other than the
filesystem changed. Plus unit tests for the two lifecycle paths above:
rebaseline with a relocated out dir, and delete-project leaving no orphan.

Tracked as task #101.

### 2. Incremental javac

**Payoff: 2.1-4.2 s per edit** `[measured on a56]` — roughly 30% of what
remains after item 1, and it applies to every edit in a Java-bearing module
regardless of what changed. Host micro-benchmark at sora's real shape (214
`.java`): all-sources + fresh file manager 308 ms, single-source + reused file
manager 7 ms — a **15x** gap on the body-edit path `[measured on host]`.

**Why.** `IncrementalCompiler.kt` passes `allSources`, not `changedFiles`, to
`JavaCompileStep.compile()`, so javac recompiles the module's whole Java half
on every edit; javac has no incremental mode of its own. Separately,
`JavaCompileStep.kt:39` builds and closes a `StandardJavaFileManager` per
compile, so the zip index of `android.jar` (27 MB) and every AAR `classes.jar`
is re-scanned every edit even though the session classpath is fixed by
construction.

**Effort: S then M. Risk: L-M then M.** Land the file-manager reuse (option B)
first: small, independently worth ~24% of the all-sources path
`[measured on host]`, and its risk surface (cache staleness) is disjoint from
the per-file change's (stale bytecode), so bugs stay attributable. Then
per-changed-file javac (option A), whose failure mode is stale bytecode — the
invariant the whole feature rests on — so its guards must be
conservative-by-default and the fallback wired before the fast path.

**What must be true first.** Two guards, per
[`incremental-javac-design.md`](incremental-javac-design.md) §3A: no `.java`
ABI moved (`JavaSourceAbi.changedTypeNames()` already computes this and already
gates the Kotlin side), and no Kotlin-emitted `.class` public API moved (new —
a public-API hash over the classes the Kotlin pass rewrote, ~150 LOC, the real
work of this item). Both behind one flag defaulted off until a green corpus
run, so the fallback is a config change rather than a revert.

**The test that proves it.** The design note's plan, of which the load-bearing
case is: *a Kotlin signature change consumed by an unchanged Java caller
recompiles that caller* — it must fail against a build with guard 2 removed, or
it is not testing the stale-bytecode failure. Plus corpus output-equivalence on
`sora-editor-full`, `sora-editor-lib`, `mixed-lang`, and `mixed-lang-cyclic`
(the mixed-language apps are the ones that can go stale).

Tracked as task #103.

### 3. Incremental dexing

**Payoff: 2.1-4.6 s per edit** `[measured on a56]`, and after item 1 it is the
largest remaining line on a Java body edit — 3.7-3.9 s of the 8.1 s edit.

**Why.** Every build dexes all 464 classes from scratch. Gradle's `dexBuilder`
dexes per-class and merges, so its one-file edit re-dexes one class. D8
supports the same per-class output plus a merge step.

**Effort: L. Risk: M.** Larger than items 1-2 and the correctness argument is
the delicate one: a merge that reuses a cached dex for a class whose input
bytes changed is a never-stale violation, and the never-stale invariant is what
lets Quick Build skip a real build at all.

**What must be true first.** A cache key derived from the post-strip class
bytes, not from mtime or source path — the same property that makes item 5
(stop re-stripping unchanged classes) correct. Sequence this after item 1: on
emulated storage the win would be partly masked by the I/O toll the strip pass
was paying anyway, and the measurement would be hard to attribute.

**The test that proves it.** A warm Java body edit re-dexes exactly one class
(assert the count, not the time), and corpus output-equivalence still passes —
the merged dex must be equivalent to a from-scratch dex of the same class set.
Then the device A/B for the time.

Tracked as task #102.

### 4. Narrow the "Java ABI moved -> recompile all Kotlin" rule

**Payoff: 14.9 s of the 22.9 s `03-java-abi-change` edit** `[measured on a56]`.
That edit is 0.42x today and still 0.52x after item 1 — the only measured edit
class that stays below a standard build.

**Why.** `IncrementalCompiler.kotlinFilesToCompile` (`IncrementalCompiler.kt:377`)
recompiles every Kotlin source whenever any `.java` ABI moves. This is
deliberate, not a bug, and its KDoc states the reason: the Build Tools API
engine has no dependency tracking over non-classpath Java sources, and the two
cheap approximations both leave stale bytecode (there is no way to inject a
non-classpath ABI change into the engine's lookup caches, and seeding from
Kotlin files that lexically name the changed type misses indirect dependents —
a `typealias` re-exports the type under a name whose own ABI does not move).

**Effort: M/L. Risk: M.** The approach is a javac `TaskListener` dependency
graph recorded at `ANALYZE` during the seeding compile, persisted, then used to
recompile only the dependent closure. A graph that under-approximates is
directly a stale-bytecode bug.

**What must be true first.** Do this last. It is blocked on the same BTA
limitation its own KDoc documents, it moves one edit class rather than all of
them, and its target should be a number measured *after* items 1-3 land — today
we do not know the post-fix split between javac and Kotlin inside that 22.9 s.

**The test that proves it.** An ABI-changing Java edit whose dependent set is
known by construction recompiles exactly that set — including the indirect
`typealias` case the KDoc names, which is the one a naive graph gets wrong.
Corpus output-equivalence on the mixed-language apps again.

Tracked as task #104.

### 5. Stop re-stripping unchanged classes

Largely subsumed by item 1 — strip falls from 4.7-5.5 s to 0.17-0.33 s once off
emulated storage `[measured on a56]`. Still the right shape (strip is a pure
function of the input bytes, so an unchanged class never needs re-stripping),
and it shares its cache key with item 3, so fold it into that work rather than
scheduling it separately. `DexTool.openClasses` (`DexTool.kt:119-123`)
`deleteRecursively()`s the strip mirror and rewrites every class, changed or
not, once per build.

## Open investigation: the c107 residual

Not a fix — a thing we do not understand, and the only measured evidence that
the A56 story is incomplete.

On the A56, the four shipped fields plus the four added for the deep-dive
account for **91%** of a warm sora edit; the residual is ~1.35 s of 14.7 s, all
of it scan, tree walks, and deploy policy — a fixed 9% `[measured on a56]`.

The c107 does not behave that way. Its `medium-kotlin` row leaves **52% of
`compileMs` unattributed**, and it got *worse* across a session — 188 s then
236 s — where the A56 got better, 53.6 s then 16.5 s `[measured on c107]` /
`[measured on a56]`. A fixed per-operation tax does not compound; something
there accumulates.

The leading candidate is JVM heap, and it is a hypothesis, not a finding. The
daemon spawns as a plain `java -jar daemon.jar` with **no `-Xmx` and no
`MaxMetaspaceSize`** (`DaemonProcessClient.kt:83-97`), and the same code
`environment().clear()`s before spawning, so `JAVA_TOOL_OPTIONS` cannot supply
them either. The JVM therefore takes ergonomic defaults from physical RAM: on
the A56 that measures **MaxHeapSize 1.81 GB, G1, metaspace unlimited**
`[measured on a56]`. The same ergonomics on a ~2 GB device give roughly a
quarter of that `[inferred]`, against a workload — kotlinc plus its incremental
caches, ASM over 464 classes, d8 — that fits comfortably in 1.8 GB and grows as
a session accumulates state. That shape fits a slowdown that compounds edit
over edit rather than a constant tax. It is consistent, and it is unproven.

**What settles it:** one c107 run. Log GC time and heap high-water from the
daemon, or set an explicit `-Xmx` and re-measure. Either outcome is useful — if
heap is not it, the residual is somewhere none of the current instrumentation
looks, and that is a bigger finding.

Note the storage finding of item 1 does **not** explain this. It should be
expected to reproduce on the c107 and be worse there, but that is `[inferred]`
— the c107 was not re-measured — and a fixed per-file toll cannot produce a
session that degrades.

Tracked as task #105.

## What this roadmap does not cover

- **The standard Gradle build's exposure to the same filesystem toll.**
  Project `build/` directories are on emulated storage too. Gradle pays less
  than Quick Build did, because its incremental tasks rewrite only what
  changed — but "less" is `[unmeasured]`, and given dexing was 56-66% of
  on-device build time in the earlier E8-E14 work, the exposure is plausibly
  large. Moving `build/` is a genuine product tradeoff (user visibility, MTP,
  portability of a copied project folder), not a free optimization; measure
  before deciding. Task #106, and the option list is in
  [`docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md).
- **The low tiers generally.** Every number above is the A56. The c107 and the
  1.9 GB tier are 4-13x slower overall and were not re-measured.
- **`readyou`.** A pure-Kotlin 6-file module measuring gen1 13.7 s / gen2
  15.2 s before dropping to 2.9 s `[measured on a56]`. No javac, no large class
  tree — none of the items above explain it. Separate investigation, task #96.

## Two facts worth not re-deriving

- **Daemon IPC is free.** Host-observed RPC time minus daemon-observed duration
  is 20-60 ms on multi-second calls `[measured on a56]`. Whatever is slow, it
  is not the protocol.
- **The "53 s per edit" figure was never a per-edit cost.** Both 53 s figures
  were edit index 1 — the session's first build. The same session's first warm
  edit was 16.5 s. Today that first build runs as a background Seed during
  provisioning, before the user can save, and the first user-visible edit is
  8.2 s `[measured on a56]`. The framing that started this investigation
  compared a cold build against a warm one.
