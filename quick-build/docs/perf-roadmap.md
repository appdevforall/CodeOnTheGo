# Backlog Discussion: Known Performance Issues (for prioritiziation / followup tickets)

## Summary

Quick Build is already a median ~3.45x faster than the incremental standard build of the same edit, over 78 edits across 23 corpus apps [measured on a56].  And this ignores any manual installation prompts that come up in the standard build.

We've identified five issues that can improve performance, roughly sorted in decreasing ROI:

| #   | Fix                                               | Affects             | Payoff per warm edit                 | Effort | Risk |
| --- | ------------------------------------------------- | ------------------- | ------------------------------------ | ------ | ---- |
| 1   | Move the daemon scratch tree off emulated storage | All apps            | **-45%** (both apps measured)        | S      | M    |
| 2   | Incremental dexing                                | All apps            | 2.1-4.6 s                            | L      | M    |
| 3a  | Reuse the javac file manager                      | Apps with Java      | ~0.5-1.0 s `[inferred]`              | S      | L    |
| 3b  | Per-changed-file javac                            | Apps with Java      | ~1.5-3.2 s more `[inferred]`         | M      | M    |
| 4   | Narrow "Java ABI moved -> recompile all Kotlin"   | Mixed Java + Kotlin | 14.9 s, but only on ABI-change edits | L      | M    |

## Scope and provenance

Sequenced plan, nothing committed. Measured on the A56 unless a claim is tagged otherwise; evidence in `quick-build/corpus/results/20260728T172912Z-sora-deepdive/` (n=1 before the change, n=2 after). Background this doc does not restate: [`docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md) and [`incremental-javac-design.md`](incremental-javac-design.md).

## Where a warm edit goes today

The stages, in the order they run:

- **javac** — compiles *all* the project's `.java` sources in-process (`-proc:none`), after Kotlin and against the Kotlin output; it is not incremental today.
- **kotlinc** — the Kotlin Build Tools API incremental compile, given an explicit changed set: just the edited `.kt` files normally, every Kotlin source when a Java ABI moved.
- **strip** — rewrites a mirror of every `.class` with `ACC_FINAL` cleared, because the generated proxy activities extend the user's classes and the dex verifier rejects a final superclass. It deletes and re-creates the whole tree each time, which is why it dominates on FUSE.
- **d8** — dexes the stripped tree into one `classes.dex`, all classes every time.
- **policy+walks** — two `Files.walk` passes over the class tree (before and after the compile) to diff mtimes into a changed-class set, plus the deploy policy that ASM-parses those class headers to decide restart / recreate / rebaseline.

Two caveats on that last column: it sums a daemon-side span with a host-side one, and the walk time is already inside the compile RPC — so it is not additive with `total` the way the other columns are.

The reference workload is `sora-editor-full` (292 sources: 218 `.java` + 74 `.kt`, producing 464 class files / 1.46 MB of bytecode) — the corpus's worst Quick Build case and the only one where Quick Build lost to a standard build. Warm edit, ms `[measured on a56]`:

| edit            | total | javac | kotlinc | strip | d8   | policy+walks |
| --------------- | ----- | ----- | ------- | ----- | ---- | ------------ |
| Java body       | 14718 | 3983  | 659     | 5492  | 3104 | 883          |
| Kotlin body     | 14922 | 2849  | 3447    | 4659  | 2421 | 1058         |
| Java ABI change | 28055 | 2677  | 16377   | 4776  | 2268 | 1465         |

## Details on Optimization Opportunities

Ordered by measured payoff per unit of risk.

### 1. Move the daemon scratch tree off emulated storage

**Status: built (2026-07-31).** `QuickBuildScratch` keys a per-project tree under the app's `noBackupFilesDir` (`quickbuild-scratch/<basename>-<sha256-prefix>/{work,out}`); the session manager wires the executor workDir and daemon outDir there, guards free space before provisioning, removes the tree on session teardown, and sweeps dead-session trees at manager start. The generation counter stays at `<project>/.androidide/quickbuild/generation` on purpose (must outlive sessions; see `FileGenerationStore`). The -45% below is still the PROTOTYPE's number - the production code has not been re-benchmarked on device yet.

**Payoff: -45% on both warm edits it was measured on** `[measured on a56]` (prototype patch)

- sora 14.7 s -> 8.1 s; `medium-kotlin` (28 sources) 2.5 s -> 1.4 s. One edit each, n=1 before and n=2 after.
- Against the 11.9 s standard incremental build, sora's Java body edit goes from 0.81x (losing) to 1.47x (winning).
- Two apps an order of magnitude apart landing on the same 45% says this is not a large-project fix — but it is two apps, so do not restate it as "every app size".

**Why.** `QuickBuildSessionManager.kt:674` sets `outDir = File(layout.projectRoot, ".androidide/quickbuild/out")`, and `layout.projectRoot` is under `/storage/emulated/0/CodeOnTheGoProjects/` — FUSE storage, a **52x** per-file toll (192 ms vs 9985 ms for the same 464-file tree) `[measured on a56]`. Only the per-file steps move under the A/B; the compute-bound controls do not, which is what makes it a filesystem finding rather than a general speedup. Per-step tables are in that run directory.

**Effort: S. Risk: M.** The measuring patch was 8 lines. The risk is not in the move, it is in everything that assumed the out dir lives under the project.

**What must be true first.**

- A cleanup policy for stale per-project work dirs. On the project side they died with the project folder; in app-private storage they accumulate forever, which matters most on the smallest tier (incar Q8, 1.46 GB).
- An audit of the rebaseline and teardown paths for code that assumes the out dir sits under the project root.
- A collision-safe directory key. The experimental patch keyed on `hashCode()` of the project path — fine to measure with, not to ship.

**The test that proves it.** A device A/B on the same build: warm `01-java-body-edit` on `sora-editor-full` and on `medium-kotlin`, expecting ~-45% on both, with `stripMs` collapsing ~20x and `kotlinMs`/`d8Ms` flat. Flat compute steps are the control — if they move too, something other than the filesystem changed. Plus unit tests for the two lifecycle paths above: rebaseline with a relocated out dir, and delete-project leaving no orphan.

Task #101.

### 2. Incremental dexing

**Payoff: 2.1-4.6 s per edit** `[measured on a56]`, pooled across all post-fix sora rows. After item 1 it is the largest remaining line on a Java body edit specifically — 3.7-3.9 s of that 8.1 s edit, measured on the g1 rows alone.

**Why.** Every build dexes all 464 classes from scratch. Gradle's `dexBuilder` dexes per-class and merges, so its one-file edit re-dexes one class. D8 supports the same per-class output plus a merge step.

**Effort: L. Risk: M.** Larger than item 1 and the correctness argument is the delicate one: a merge that reuses a cached dex for a class whose input bytes changed is a never-stale violation, and the never-stale invariant is what lets Quick Build skip a real build at all.

**What must be true first.** A cache key derived from the post-strip class bytes, not from mtime or source path — the same property that makes item 5 correct. Sequence this after item 1: on emulated storage the win would be partly masked by the I/O toll the strip pass was paying anyway, and the measurement would be hard to attribute.

**The test that proves it.** A warm Java body edit re-dexes exactly one class (assert the count, not the time), and corpus output-equivalence still passes — the merged dex must be equivalent to a from-scratch dex of the same class set. Then the device A/B for the time.

Task #102.

### 3a + 3b. Incremental javac

**Payoff: 2.1-4.2 s per edit, pooled across post-fix sora rows** `[measured on a56]`

- Roughly 30% of what remains after item 1, and it applies to every edit in a Java-bearing module regardless of what changed.
- Host micro-benchmark at sora's real shape (214 `.java`): all-sources + fresh file manager 308 ms, single-source + fresh file manager 20 ms — a 15x gap on the body-edit path; reusing the file manager takes the single-source path to 7 ms `[measured on host]`.

**Why.** `IncrementalCompiler.kt` passes `allSources`, not `changedFiles`, to `JavaCompileStep.compile()`, so javac recompiles the module's whole Java half on every edit; javac has no incremental mode of its own. Separately, `JavaCompileStep.kt:39` builds and closes a `StandardJavaFileManager` per compile, so the zip index of `android.jar` (27 MB) and every AAR `classes.jar` is re-scanned every edit even though the session classpath is fixed by construction.

These are two separate changes and should be scheduled as such, because their risk surfaces are disjoint — landing them apart keeps bugs attributable. 3a, reusing the javac file manager, is Effort S / Risk L: independently worth ~24% of the all-sources path `[measured on host]`, and its failure mode is cache staleness, which is contained and cheap to test. 3b, per-changed-file javac, is Effort M / Risk M: the bulk of the win and the delicate one, because its failure mode is stale bytecode — the invariant the whole feature rests on — so its guards must be conservative-by-default and the fallback wired before the fast path. Land 3a first: it is small, it banks a real win, and it stands on its own if 3b is later deferred or reverted.

**What must be true first.** Two guards, per [`incremental-javac-design.md`](incremental-javac-design.md) §3A:

- No `.java` ABI moved. `JavaSourceAbi.changedTypeNames()` already computes this and already gates the Kotlin side.
- No Kotlin-emitted `.class` public API moved. New — a public-API hash over the classes the Kotlin pass rewrote, ~150 LOC, the real work of this item.

Both behind one flag defaulted off until a green corpus run, so the fallback is a config change rather than a revert.

**The test that proves it.** The design note's plan, of which the load-bearing case is: *a Kotlin signature change consumed by an unchanged Java caller recompiles that caller* — it must fail against a build with guard 2 removed, or it is not testing the stale-bytecode failure. Plus corpus output-equivalence on `sora-editor-full`, `sora-editor-lib`, `mixed-lang`, and `mixed-lang-cyclic` (the mixed-language apps are the ones that can go stale).

Task #103.

### 4. Narrow the "Java ABI moved -> recompile all Kotlin" rule

**Payoff: 14.9 s of the post-storage-fix 22.9 s ****`03-java-abi-change`**** edit** `[measured on a56]`

- Say which 22.9 s: that edit costs **28.1 s today** (the 28055 ms in the table above, variant A) and 22.9 s after item 1 lands.
- Against the 11.9 s standard build that is 0.42x today and still 0.52x after item 1 — the only measured edit class that stays below a standard build either way.

**Why.** `IncrementalCompiler.kotlinFilesToCompile` (`IncrementalCompiler.kt:377`) recompiles every Kotlin source whenever any `.java` ABI moves. This is deliberate, not a bug, and its KDoc states the reason: the Build Tools API engine has no dependency tracking over non-classpath Java sources, and the two cheap approximations both leave stale bytecode (there is no way to inject a non-classpath ABI change into the engine's lookup caches, and seeding from Kotlin files that lexically name the changed type misses indirect dependents — a `typealias` re-exports the type under a name whose own ABI does not move).

**Effort: L. Risk: M.** The approach is a javac `TaskListener` dependency graph recorded at `ANALYZE` during the seeding compile, persisted, then used to recompile only the dependent closure. A graph that under-approximates is directly a stale-bytecode bug.

**What must be true first.** Do this last. It is blocked on the same BTA limitation its own KDoc documents, it moves one edit class rather than all of them, and its target should be a number measured *after* items 1-3 land — today we do not know the post-fix split between javac and Kotlin inside that 22.9 s.

**The test that proves it.** An ABI-changing Java edit whose dependent set is known by construction recompiles exactly that set — including the indirect `typealias` case the KDoc names, which is the one a naive graph gets wrong. Corpus output-equivalence on the mixed-language apps again.

Task #104.

### 5. Stop re-stripping unchanged classes

Largely subsumed by item 1 — strip falls from 4.7-5.5 s to 0.17-0.33 s once off emulated storage `[measured on a56]`. Still the right shape (strip is a pure function of the input bytes, so an unchanged class never needs re-stripping), and it shares its cache key with item 2, so fold it into that work rather than scheduling it separately. `DexTool.openClasses` (`DexTool.kt:119-123`) `deleteRecursively()`s the strip mirror and rewrites every class, changed or not, once per build.

## What we ruled out, and what it cost us

Recorded so nobody re-derives them. Evidence: `quick-build/corpus/results/20260728T172912Z-sora-deepdive/`.

**Two hypotheses the measurements killed.** Both were plausible from code reading alone; both were wrong.

- *"The Java-ABI gate fails open, silently recompiling all Kotlin on a Java edit."* The gate is healthy — a Java body edit measured `nKotlinToCompile=0` `[measured on a56]`. The 74-file Kotlin recompile is real, but only on a genuine ABI change, which is item 4's documented design.
- *"Non-incremental dexing is the dominant cost."* Half right. Dex **is** 48-60% of the edit, but most of that was the strip pass's file I/O, not dexing compute — strip fell 20x when only the filesystem changed.

**A correction to **[**`incremental-javac-design.md`**](incremental-javac-design.md)**.** Its options A+B are still right — they are items 3a and 3b — but its framing was wrong, and it is worth knowing why:

- It calls javac "the bottleneck". javac is **19-27%** of a warm edit before the storage fix and 25-36% after.
- It only looked *inside* `compileMs`, where javac is 55-61% on the small Java apps it sampled — and `compileMs` in those rows **excludes dex**. The dex half was the larger half, and the filesystem cost underneath both was invisible to every field the note had.
- Its device-scaling factor was also off: it inferred ~40x host from 4-file rows; the A56 measures **7-14x** at N=218 `[inferred]`.

**The analytics lesson, which is why the residual field now exists.** The shipped fields (`kotlinMs`, `javacMs`, `stripMs`, `d8Ms`) summed to about **half** a warm edit, and the dominant cost lived entirely in the unmeasured half. That is how a careful design note reached a wrong conclusion from real device data. The durable fix was not more fields but an explicit **unaccounted residual** in the analytics event: when a future change adds an expensive un-timed step, the residual grows and the next reader sees it instead of misattributing the cost.

Incidental, also from that run: **daemon IPC is free** — host-observed RPC minus daemon-observed duration is 20-60 ms on multi-second calls `[measured on a56]`.

## What this roadmap does not cover

- **The standard Gradle build's exposure to the same filesystem toll.** Project `build/` directories are on emulated storage too, and how much a standard build pays for that is `[unmeasured]`. Moving them is a genuine product tradeoff, not a free optimization; the option list and its costs are in [`docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md). Task #106.
- **The low tiers generally.** Every number above is the A56. The C107 and the 1.9 GB tier are 4-13x slower overall and were not re-measured.
- **`readyou`****.** A pure-Kotlin 6-file module measuring gen1 13.7 s / gen2 15.2 s before dropping to 2.9 s `[measured on a56]`. No javac, no large class tree — none of the items above explain it. Separate investigation, task #96.

Two facts worth not re-deriving, both evidenced in the sora deep-dive run: **daemon IPC is free** (20-60 ms on multi-second calls `[measured on a56]`), and **the "53 s per edit" figure was never a per-edit cost** — it was the session's first build, which today runs as a background Seed before the user can save.
