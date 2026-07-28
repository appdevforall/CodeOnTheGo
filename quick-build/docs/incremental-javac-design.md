# Incremental javac in the Quick Build daemon

- **Status:** design only — no code change in this commit.
- **Date:** 2026-07-28
- **Ticket:** ADFA-4128, task 60.
- **Scope:** the daemon's `.java` compile pass. The Kotlin pass, dex, and relink are
  out of scope except where they gate a javac decision.
- **Read [`sora-slow-path-gap.md`](sora-slow-path-gap.md) first.** That scope line is
  also this note's limitation: `compileMillis` is stamped after dexing, so javac is
  roughly one sixth of the bucket this note tries to explain, and the measured
  slow case (~53 s on sora-editor-full) is dominated by costs outside that scope —
  most likely non-incremental dexing plus a Java-ABI gate that fails open and
  silently forces a full Kotlin recompile. Land the fixes in that document's order,
  not this one's, unless the pending measurement says otherwise.

Claim provenance is tagged throughout: `[measured on host]` = Mac Mini, Zulu JDK 17.0.12;
`[measured on a56]` / `[measured on c107]` = the 2026-07-24 device e2e sweep
(`test_app_corpus/results/qb-report-review.html`); `[inferred]` = reasoning over those.
Untagged sentences are code reading, which is verifiable from the file references.

## 1. The current invocation path

`IncrementalCompiler.compile()` runs two passes into one output dir
(`quickbuild-daemon/.../compile/IncrementalCompiler.kt`):

1. **Kotlin** — `compileKotlin()`, Build Tools API, genuinely incremental. Kotlin gets
   the `.java` files in its source list for symbol resolution only; it emits no Java
   bytecode.
2. **Java** — `JavaCompileStep.compile()`, the JDK's in-process `javax.tools` javac.

The Java pass is where the problem is. Three properties, all in
`IncrementalCompiler.kt:156-168` and `JavaCompileStep.kt:24-58`:

- **Every `.java` source in the module is passed on every compile.**
  `val javaSources = allSources.filter { it.extension == "java" }` — `allSources`, not
  `changedFiles`. javac has no incremental mode of its own, so this is a full recompile
  of the module's Java half per edit. `IncrementalCompiler`'s own KDoc says so
  ("javac's pass is not incremental: user projects here are Kotlin-first and stray Java
  files are small") — a stated assumption the corpus has since falsified for
  `sora-editor-full`.
- **The file manager is rebuilt and closed on every compile.**
  `compiler.getStandardFileManager(...)` inside `fileManager.use { ... }` — so the zip
  index of `android.jar` (27 MB) and of every AAR `classes.jar` on the session classpath
  is re-opened and re-scanned per edit, even though the session classpath is fixed by
  construction (`IncrementalCompiler`'s `init` snapshots it once; a classpath change is a
  session invalidation).
- **No sourcepath / no implicit control.** With the whole source set handed in
  explicitly this is currently moot, but it is the first thing a per-file variant has to
  pin down (section 4).

Adjacent, and NOT the bottleneck: `JavaSourceAbi.snapshot()` re-parses all `.java`
sources every compile too, but parse-only — 44 ms for 214 files vs 308 ms for the full
compile of the same set [measured on host]. Keep it.

The pass is already instrumented end to end: `IncrementalCompiler.Result.javaMillis` ->
daemon `javaMillis` -> `CompileOutput.javaMillis` -> `E2eTimeline` ->
`BenchQuickBuildMetricsSink`'s `javacMs`. Nothing new is needed to measure a fix.

## 2. Why it is the bottleneck — and where it is not

### 2.1 javac cost is linear in TOTAL `.java` count, not changed count

Host micro-benchmark: N synthetic `.java` files (8 methods each, referencing each other
and `android.util.Log`/`android.os.Bundle`), classpath = `android-35/android.jar`, JDK
17.0.12, median of 5 iterations [measured on host]:

| `.java` in module | all-sources + fresh file manager (today) | all-sources + reused file manager | 1 source + fresh fm | 1 source + reused fm | ABI parse-only pass |
|---|---|---|---|---|---|
| 4   | 30 ms  | 20 ms  | 20 ms | 10 ms | 3 ms  |
| 50  | 120 ms | 74 ms  | 22 ms | 8 ms  | 10 ms |
| 214 | 308 ms | 235 ms | 20 ms | 7 ms  | 44 ms |

Read: the all-sources path grows ~1.35 ms/file with a ~17 ms floor; the single-file path
is **flat in N** (~20 ms fresh, ~7 ms warm). At `sora-editor-full`'s real shape (214
`.java`) that is a **15x** gap on the body-edit path, and reusing the file manager is
worth a further ~24% on the all-sources path / ~65% on the single-file path.

### 2.2 On device, javac is the majority of a warm compile once Java is present

The only device rows carrying the per-step breakdown are the c107 ones (the a56 sweep
recorded `javacMs` as null) [measured on c107], warm edits only:

| app | `.java` files | edit | kotlinMs | **javacMs** | stripMs | d8Ms | compileMs |
|---|---|---|---|---|---|---|---|
| kiss | 4 | 05-clipboard-contextcompat | 0 | **948** | 212 | 424 | 1722 |
| kiss | 4 | 03-nonnull-tostring | 1 | **1220** | 232 | 490 | 2068 |
| antennapod-model | 4 | 03-feedfunding-serializable | 1 | **1253** | 229 | 424 | 2059 |
| medium-kotlin | 0 | 03-new-class | 4093 | **0** | 929 | 940 | 12495 |

javac is 55-61% of warm `compileMs` on the Java apps, and exactly 0 where there is no
Java. At 4 files that ~1.2 s is essentially all fixed cost — the c107 is roughly 40x the
host on this step [inferred, 1200 ms / 30 ms].

`sora-editor-full` has **214 `.java` + 74 `.kt`** (counted in the sweep's materialized
work tree). Scaling the host curve by the observed device factors: ~3 s of javac per edit
on the a56 and ~12 s on the c107, every edit, whatever changed [inferred]. Its warm rows
[measured on a56]:

| app | edit | qb ms | std ms | speedup |
|---|---|---|---|---|
| sora-editor-full | 02-kotlin-body-edit | 34767 | 11909 | **0.34x** |
| sora-editor-full | 03-java-abi-change | 29934 | 11909 | **0.40x** |
| sora-editor-lib (14 `.java`) | 02-sample-app-ui | 7428 | 5962 | 0.80x |

### 2.3 Correction: assets-app and readyou are NOT javac

The task framing named `assets-app`, `readyou`, and `sora-editor-full` as one javac tail.
Only the third is. Source counts from the same work tree:

| app | `.java` | `.kt` | slow warm row | javac cost |
|---|---|---|---|---|
| sora-editor-full | 214 | 74 | 0.34x, 0.40x | real |
| sora-editor-lib | 14 | 2 | 0.80x | small |
| readyou | **0** | 6 | 02-number-infix 15163 ms vs 5859 ms = 0.39x | **zero** |
| assets-app | **0** | 2 | 02-method-body 11827 ms = 0.43x; 03-asset-plus-code 6775 ms = 0.74x | **zero** |

`JavaCompileStep` is not even called when there are no `.java` sources
(`IncrementalCompiler.kt:159`), so javac contributes exactly 0 ms to `readyou` and
`assets-app`. Their slow rows are something else:

- `assets-app` edit 01 is asset-only and never reaches the compiler, so **edit 02 is the
  session's first code edit** — the known ~12 s cold-kotlinc artifact, not a steady-state
  cost. Its genuinely-warm row is 03 at 0.74x, which is asset packaging plus a Kotlin
  recompile.
- `readyou`'s whole session reads cold (gen 1 = 13.7 s, gen 2 = 15.2 s, gen 3 = 2.9 s at
  2.01x). A 6-file Kotlin module taking 15 s on gen 2 is not explained by any javac work
  and needs its own investigation — likely a daemon re-seed between edits. **Filed as a
  separate follow-up; incremental javac will not move it.**

So the honest scope of this work: **it fixes `sora-editor-full`-shaped modules (Java-heavy,
Kotlin-mixed) and takes ~1 s off every warm edit in small Java apps like `kiss` and
`antennapod-model`. It does nothing for the 0-Java rows in the tail.**

### 2.4 The second-order cost: an ABI-changing Java edit also recompiles all Kotlin

`kotlinFilesToCompile()` recompiles **every** Kotlin source whenever any `.java` file's
ABI moves, because the BTA engine has no dependency tracking over non-classpath Java
sources. That rule is sound and deliberate, but it means `sora-editor-full`'s
`03-java-abi-change` pays a full 74-file Kotlin recompile *and* a full 214-file javac in
the same build (29.9 s [measured on a56]). Incremental javac alone does not touch this
path; see option D.

## 3. Options

### A — per-changed-file javac, gated on ABI stability

Pass javac only the changed `.java` files; resolve everything else from already-compiled
`.class` files by putting the daemon's own `classesDir` on the javac classpath (it is
already there, `IncrementalCompiler.kt:164`). Add `-implicit:none` and an empty
`-sourcepath` so javac can never silently pull an unchanged source back in and re-emit it.

Soundness needs two guards, both cheap and both already half-built:

1. **No `.java` ABI moved** — `JavaSourceAbi.changedTypeNames()` already computes exactly
   this and already gates the Kotlin side. If any Java ABI moved, fall back to compiling
   all `.java` (today's behaviour). This covers Java-depends-on-Java.
2. **No Kotlin-emitted `.class` public API moved** — new, and the part that is missing
   today. A Kotlin edit that changes a signature must recompile the Java files that call
   it, or they keep bytecode bound to the old descriptor (a NEVER-STALE violation). The
   daemon already diffs its output tree (`snapshotClassOutputs`) and already depends on
   ASM (`dex/FinalStripper.kt`), so a public-API hash over the `.class` files the Kotlin
   pass rewrote is a small addition. Body-only Kotlin changes leave the hash stable and
   keep the fast path; any public-API move falls back to full javac.

- **Effort:** M — the guard-2 class-ABI hasher is the real work (~150 LOC + tests); the
  javac call change is a handful of lines.
- **Risk:** M. The failure mode is stale bytecode, which is the invariant the whole
  feature rests on, so the guards must be conservative-by-default (unknown -> full javac,
  matching how `JavaSourceAbi.snapshot()` returning null is handled today). Mitigated by
  the corpus's output-equivalence check, which compares QB output against a real build.
- **Win:** ~15x on the Java-body-edit path at 214 files; ~1x (no change) on the
  ABI-change path [measured on host, extrapolated to device as inferred].

### B — session-scoped, reused `StandardJavaFileManager`

Create the file manager once per session and stop closing it per compile. The session
classpath is fixed by construction, which is precisely the invariant that makes the
cached zip indexes safe; `IncrementalCompiler` already relies on it for
`assureNoClasspathSnapshotsChanges(true)`.

- **Effort:** S — move the manager into `IncrementalCompiler`'s lifetime, close it on
  daemon shutdown, call `flush()` between compiles so output writes land.
- **Risk:** L-M. `StandardJavaFileManager` caches directory listings and zip entries; a
  *new* `.java` file added to an already-listed source directory is the case to watch —
  but the daemon hands javac explicit `File` objects, never a source-path directory, so
  discovery does not go through the cache. Still needs an explicit test for
  "add a new `.java` file mid-session" and for "delete a `.java` file mid-session".
- **Win:** ~24% off the all-sources path, ~65% off the single-file path
  [measured on host]. Composes with A and is worth landing even alone.

### C — swap javac for ECJ (Eclipse Compiler for Java) incremental

- **Effort:** L. A new ~3 MB compiler dependency on a daemon we are actively shrinking
  (`stageDaemonShrunk` exists to claw back 5.7 MB), a second Java front-end in a tree
  that already carries a repackaged javac fork for the language server
  (`composite-builds/build-deps/java-compiler`, the `jdkx.*` classes), a new diagnostics
  mapping, and an EPL license audit.
- **Risk:** H. ECJ's incremental builder is part of the JDT *core builder*, not a
  standalone API — using it means reimplementing the state machine anyway. ECJ also
  differs from javac on generics inference and nullability corners, so bytecode could
  diverge from what the real Gradle build produces, which breaks the corpus's
  output-equivalence gate rather than passing it.
- **Win:** no measured advantage over A+B for this shape. **Reject.**

### D — javac `TaskListener` dependency graph, to narrow the ABI-change fallback

Attach a `com.sun.source.util.TaskListener` to the seeding compile, record per-type
dependencies at `ANALYZE`, persist them, and on a Java ABI change recompile only the
dependent closure instead of all `.java` — and, longer term, feed the same graph into the
Kotlin side so an ABI-changing Java edit stops forcing a full Kotlin recompile
(section 2.4).

- **Effort:** M/L. **Risk:** M — a dependency graph that under-approximates is a
  stale-bytecode bug, and the Kotlin-side half is blocked on the same BTA limitation
  documented in `kotlinFilesToCompile`'s KDoc (no way to inject a non-classpath ABI change
  into the engine's lookup caches).
- **Win:** only the ABI-change path (`sora-editor-full` 03, 29.9 s [measured on a56]).
  Unmeasured — we do not yet know the split between javac and Kotlin in that 29.9 s.
- **Defer.** It is the right follow-up, but it should be sequenced *after* A+B land and
  the a56 sweep is re-run with `javacMs` populated, so the target is a measured number.

## 4. Recommendation

**Land B, then A, both behind one flag; reject C; defer D.**

- One flag, `quickbuild.javac.incremental`, default off until a green corpus run — the
  same shape as `stageDaemonShrunk`'s opt-in staging. Off = today's exact behaviour, so
  the fallback is a config change, not a revert.
- **B first** because it is small, independently valuable, and its risk surface
  (file-manager caching) is disjoint from A's (stale bytecode) — bugs stay attributable.
- **A second**, with the fallback to full javac wired *before* the fast path, so the
  worst case of a guard bug is today's speed, not wrong output.
- Order matters for measurement too: B's win is visible in `javacMs` on every Java app,
  so it validates the instrumentation before A changes the semantics.

Preconditions before writing A:

1. **Re-run the a56 sweep with a `javaMillis`-reporting daemon.** The a56 rows in the
   current report have `javacMs` null, so every a56 javac claim in this doc is
   `[inferred]` from the c107 breakdown and the host curve. A+B's payoff is stated as a
   ratio; the device baseline has to be measured before we can say it landed.
2. **Split `readyou` off into its own ticket.** It is in the slowdown tail and has zero
   `.java` files; bundling it here would let a real regression hide behind a javac fix
   that cannot possibly address it.

Test plan for A+B (all in `:quickbuild-daemon:test`, next to `IncrementalCompilerTest`):

- Java body edit in a multi-`.java` module -> only the changed file's `.class` is
  rewritten; the others' mtimes are untouched.
- Java ABI edit -> falls back to full javac; dependent Java classes are rewritten.
- **Kotlin signature change consumed by an unchanged Java caller** -> the Java caller is
  recompiled. This is the guard-2 test and the one that would catch the stale-bytecode
  failure; it must fail against a build with guard 2 removed.
- New `.java` file added mid-session, and `.java` file deleted mid-session -> both correct
  with the reused file manager (guards B's cache risk; deletion must still go through
  `deleteRemovedJavaOutputs`).
- Compile error in the changed file -> diagnostics unchanged in shape, and the ABI
  baseline is not promoted (the existing `javaAbi = pendingJavaAbi` discipline).
- Corpus output-equivalence on `sora-editor-full`, `sora-editor-lib`, `mixed-lang`, and
  `mixed-lang-cyclic` — the mixed-language apps are the ones that can go stale.

What would falsify the recommendation: if the re-run a56 sweep shows `javacMs` is a small
fraction of `sora-editor-full`'s 34.8 s warm compile, the tail is the Kotlin pass over 74
files, not javac, and the effort belongs in option D / the BTA path instead.

## 5. Reproducing the host numbers

The host micro-benchmark is throwaway (not committed): two single-file Java programs run
with `java Bench.java <srcRoot> <outRoot> <android.jar> <N> <iters>` and
`java Parse.java <srcRoot> <iters>` under `flox activate -d flox/local`. `Bench` generates
N synthetic sources, seeds a full compile, then times four modes — all-sources/fresh
manager (today), all-sources/reused, one-source/fresh, one-source/reused. `Parse` times
`JavacTask.parse()` over the same set, which is what `JavaSourceAbi.snapshot()` does.
Re-derive rather than trust the table if the JDK or classpath changes.
