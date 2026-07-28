# Gap: why Quick Build is slower than a standard build on sora-editor-full

Status: open gap, not scheduled. Written 2026-07-28 so the analysis is not lost.
One decisive measurement is still outstanding (see "What would settle it").

Provenance tags are mandatory here: `[measured on a56]`, `[measured on c107]`,
`[measured on host]`, `[inferred]`. Untagged prose is framing, not a claim.

## The problem

`sora-editor-full` is the corpus app where Quick Build loses worst to a plain
incremental Gradle build. It is the real sora-editor `:editor` module: 287 mixed
Kotlin/Java sources with bidirectional Kotlin <-> Java references.

| device | QB save->live | standard incremental | ratio |
|---|---|---|---|
| A56 | 53.6 s / 53.0 s / 16.5 s / 29.9 s across edits | 11.9 s | ~0.4x (QB ~4.5x slower) |
| C107 | 188 s / 236 s / 97 s | 53.6 s | ~0.5x |

`[measured on a56]`, `[measured on c107]`. Caveat: these rows predate the
per-edit attribution fix (task #57), which found 21% of recorded edits were
joined to the wrong build, so individual values may be off. The gap is far too
large to be an artifact, but do not quote a specific millisecond figure from
this table without re-measuring.

Two facts narrow the search a lot:

- The cost is in compile, not delivery: `qbCompileMs` is 53,508 of 53,650 ms
  `[measured on a56]`.
- It is not a fallback. Every edit routed `CodeOnly` — the fast path itself is
  slow, Quick Build is not silently degrading to a rebaseline `[measured on a56]`.

## Why the existing javac design note does not explain it

`incremental-javac-design.md` blames javac being handed all sources with a fresh
`StandardJavaFileManager` each compile. That cost is real but small, and the note
cannot account for the number:

- Its own host micro-benchmark measures all-sources javac at ~308 ms for 214
  files `[measured on host]`. Even a 20x device penalty is ~6 s against 53 s.
- The deeper issue is a framing error. The 53 s is
  `E2eTimeline.compileMillis` = `compileDone - trigger`, and `markCompileDone` is
  stamped **after the dex step** (`QuickBuildExecutorImpl.kt:336`). So the bucket
  holds six things — source-tree walk, Kotlin compile, javac, the deploy-policy
  class-header pass, the ACC_FINAL strip, and d8 — and the note analyses one of
  them.
- The note's own device data shows the hole: on C107 `medium-kotlin`,
  kotlin 4093 + javac 0 + strip 929 + d8 940 = 5962 ms against a reported
  `compileMs` of 12495 `[measured on c107]`. **52% of compile time is
  unattributed even on a small app.**

Correcting that note is part of closing this gap.

## What actually happens on one save

For a one-line Java method-body edit, 14 of 17 pipeline steps scale with **total
project size** rather than with what changed; only coalescing and change
classification scale with the edit `[inferred, from code]`. The same is true of a
Kotlin edit, which still pays the full 214-file javac, the full Java-ABI parse,
and the full class-header pass, because none of those are conditioned on what
changed. That is why the Kotlin body edit (34.8 s) measures *slower* than the
Java ABI change (29.9 s) on this app `[measured on a56]`.

The costs that scale with project size, ranked by plausible contribution
`[inferred, from code]`:

1. **d8 is fully non-incremental.** `DexTool.kt:73-101` hands d8 every class in
   the tree and re-reads the ~27 MB `android.jar` on every save. No
   `setIntermediate(true)`, no per-class dex cache, no merge step.
2. **The ACC_FINAL strip mirror is rebuilt from zero every save.**
   `DexTool.kt:108-126` calls `deleteRecursively()` on the mirror, then re-reads
   and ASM round-trips every `.class` (`FinalStripper.kt`).
3. **javac over all `.java` sources with a fresh file manager**
   (`IncrementalCompiler.kt:156`, `JavaCompileStep.kt:39`) — the note's cost.
4. **The deploy-policy class-header pass** (`QuickBuildExecutorImpl.kt:330,351-366`,
   `ClassHeader.kt:33-71`) parses the full constant pool of every *changed* class
   — and because javac rewrites every Java-derived class each save, "changed"
   means the whole Java half. One project-size cost manufactures another.
5. **`snapshotClassOutputs()` runs twice per compile** (`IncrementalCompiler.kt:141,181`)
   — two full stat sweeps of the output tree.
6. **`JavaSourceAbi.snapshot()` re-parses all `.java` and builds a *second* file
   manager** (`JavaSourceAbi.kt:54-76`), so `android.jar` is indexed twice per save.
7. **Whole-tree walks outside the daemon**: `layout.allSources()` per build, plus
   the watcher's 2 s poll sweep (`AndroidProjectWatcher.kt:159-165,253`) — roughly
   26 extra full-tree stats during a 53 s build.

## The two candidates

**A. The dex stage is the floor.** Always present, no failure required.
Extrapolating items 1-6 against the C107 breakdown lands around 25-30 s of
C107-scale work `[inferred]` — real, but short of 53 s on the *faster* A56.

**B. A Java edit can force a full Kotlin recompile, silently.** This is the
cliff, and it fits the shape of the data (2-4 s on small apps, 53 s here) in a
way a slope does not.

`IncrementalCompiler.kt:341-356` gates Kotlin work on a Java ABI diff. Designed
behavior is right: a Java *body* edit does not move the ABI fingerprint, so only
genuinely-changed Kotlin files recompile. But **the gate fails open** — if
`JavaSourceAbi.snapshot()` returns null it returns *every* Kotlin source as
changed, and the Kotlin compiler recompiles all 74 files with no error, no
warning, and no user-visible signal.

`snapshot()` has two silent-null paths (`JavaSourceAbi.kt:71-75`): an exception
catch, and a size check. The size check is the suspicious one, because the map is
keyed by a URI round-trip — `File(unit.sourceFile.toUri()).absolutePath`
(`JavaSourceAbi.kt:67`). On Android that is exactly where path identity breaks:
`/sdcard` vs `/storage/emulated/0`, or any symlinked project root, yields a key
that does not match the input path, one entry goes missing, the size check fails,
and the gate fails open **on every save, forever**.

Best current read: **A is the floor, B is why the number is 53 s rather than
~25 s.** They are not exclusive; expect both `[inferred]`.

## Why we cannot currently tell which

`IncrementalCompiler` computes exactly the diagnostic that would answer this —
`lastJavaAbiChange` (line 104) and `lastCompileLog` (line 96) — and
`DaemonService.compile` (`DaemonService.kt:84-122`) never reads either. The
signal is generated and discarded.

Compounding it: per-step timings exist in only 13 of 466 rows in the whole
benchmark dataset, all from one C107 run. There are none on the A56 and none for
sora on any device, so no breakdown of these 53 s has ever been recorded.

## What would settle it

Ranked by information per unit of effort. Items 1-2 are already computed and
returned by the daemon and were merely recorded as null in the A56 sweep — this
is likely a reporting fix, not new instrumentation.

1. **`kotlinMillis` for a one-line Java method-body edit on this app**
   (`DaemonService.kt:107`). Near zero kills candidate B; seconds-to-tens
   confirms it. Single highest-value measurement.
2. **`stripMillis` + `d8Millis` for the same edit** (`DaemonService.kt:136-137`)
   — sizes candidate A directly.
3. **The residual**: `compileMillis - (kotlin + javac + strip + d8)`. A large
   residual means the cost is in the un-timed steps (tree walks, the two
   snapshot sweeps, the ABI parse, the class-header pass).
4. **Actual `.class` count in the output tree** — items 1, 2, 4, 5 scale on that,
   not on source count; with inner classes and Kotlin lambdas it is likely 2-4x
   the 287 sources, which changes every extrapolation above.
5. **Whether `JavaSourceAbi.snapshot()` returns null on device.** One log line at
   `IncrementalCompiler.kt:351`. If null, log `result.size` vs
   `javaSources.size` plus one example key of each form, to separate the
   path-identity theory from the exception path.

## Related finding: the daemon runs with no heap flags

`DaemonProcessClient.kt:73-88` spawns the compile daemon with **no `-Xmx`, no
`-XX:MaxMetaspaceSize`, no heap flags at all**. On a low-RAM device the heap
fills across successive compiles and GC cost grows superlinearly.

This is the leading explanation for the otherwise-backwards C107 numbers — 188 s
then 236 s, i.e. the *second* edit slower — while the A56 improved across edits
(53.6 s then 16.5 s) as the preserved caches predict `[inferred]`. Other
candidates for that inconsistency: whether the background seed completed before
edit 1 (`BuildOrchestrator.kt:125` drops a seed request when a build is in
flight), a daemon respawn between edits (which resets the in-memory `javaAbi` and
guarantees a full-Kotlin compile next save), and the double-fire watcher bug
(task #83).

## Proposed fix order (when this is scheduled)

1. **Make dexing incremental.** `IncrementalCompiler.kt:208-212` already computes
   the changed-class set and throws it away. Stop wiping the mirror, strip only
   classes whose (size, mtime) moved, give d8 `setIntermediate(true)` plus a
   per-class dex cache and a merge step. Largest structural win, needs no new
   information.
2. **Instrument, then defend, the Java-ABI gate.** Plumb `lastJavaAbiChange` and
   `kotlinMillis` into the daemon response so a full-Kotlin compile is never
   silent again; key `JavaSourceAbi` on a canonical path instead of a URI
   round-trip; make the null path log loudly rather than fail open quietly.
3. **The javac note's options B then A** (reuse the file manager, narrow javac's
   input). Worth doing, but a smaller win than that note implies.

Item 4 in the ranked list mostly disappears on its own once javac stops rewriting
every class, because the changed set feeding it collapses to what really changed.

## Scope note

This gap is about the *large mixed-language* case. It is not the common case: on
the same A56, Quick Build is a median 2.5x faster than an incremental standard
build across 21 apps `[measured on a56]`. Fixing it widens the set of real-world
projects Quick Build helps rather than hurts; it does not block the v1 story.
