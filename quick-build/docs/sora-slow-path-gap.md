# Why Quick Build was slower than a standard build on sora-editor-full

Status: root-caused and measured 2026-07-28. The top fix is **not committed** — see
"Fix 1" for what shipping it responsibly still needs.

Provenance tags are mandatory: `[measured on a56]`, `[measured on c107]`,
`[measured on host]`, `[inferred]`. Untagged prose is code reading.

Evidence: `quick-build/corpus/results/20260728T172912Z-sora-deepdive/`
(per-step tables, both variants' logcat, the microbenchmark). A56, CoGo builds
`C-d-0728-1026` / `C-d-0728-1044` from `5d2d94a78` plus scratch instrumentation.

## Headline

The daemon's compile/dex scratch tree lives on Android's **FUSE-backed emulated
storage**, where per-file I/O costs ~50x what it costs on app-private storage.
Quick Build rewrites the whole class tree every build, so it pays that tax on
every save; Gradle, rewriting only what changed, does not.

Moving the scratch tree to app-private storage cuts a warm sora edit
**14.7 s -> 8.1 s (-45%)** and `medium-kotlin` **2.5 s -> 1.4 s (-45%)**. Against
the 11.9 s standard incremental build, Quick Build goes from **0.81x (losing) to
1.45x (winning)** `[measured on a56, n=2 sora / n=1 medium]`.

**And the famous "53 s" was never a per-edit cost.** Both 53 s figures are *edit
index 1* — the session's first build. That session's first warm edit was 16.5 s.
On today's build the equivalent full build still costs ~51 s but runs as a
background Seed during provisioning, before the user can save; the first
user-visible edit is now **8.2 s** `[measured on a56]`. The framing that started
this investigation compared a cold build against a warm one.

## The measured breakdown

`total` = `reloadLive - trigger` from CoGo's own `reload_timeline`; `acct` = the
independent sum of measured spans. **`acct` reconciles to `total` within 5 ms on
all 13 rows**, so nothing hides in an unmeasured gap. Times in ms
`[measured on a56]`.

Variant A — scratch under the project root (today's shipping behaviour):

```
app/gen     total  scan compile  kotlin  javac abiSnp walks policy   dex  strip    d8   acct
sora/g1     14718   246    4912     659   3983    621   268    615  8819   5492  3104  14716
sora/g2     14922   260    6545    3447   2849    453   248    810  7222   4659  2421  14920
sora/g3     28055   242   19293   16377   2677    407   237   1228  7209   4776  2268  28053
```

- **g1** (one-line Java body edit): the dex step is **8.8 s — 60% of the edit** —
  and inside it the ACC_FINAL **strip pass is 5.5 s, larger than d8 itself
  (3.1 s)**. javac is 4.0 s (27%). Kotlin is 0.7 s with `nKotlinToCompile=0`.
- **g2** (Kotlin body edit): javac still ran over all 218 Java sources for a
  `.kt`-only change (2.8 s).
- **g3** (Java ABI change): kotlinc 16.4 s, because a moved Java ABI forces a
  recompile of all 74 Kotlin sources by design (`nKotlinToCompile=74`).

Variant B — identical code, scratch on app-private storage:

| step (sora) | A (emulated) | B (app-private) | change |
|---|---|---|---|
| strip | 4659-5492 | 168-332 | **~20x faster** |
| deploy policy | 615-1228 | 64-111 | **~10x faster** |
| output-tree walks | 237-268 | 26-54 | **~6x faster** |
| kotlinc (1 file) | 3447 | 3264-3352 | ~3% (noise) |
| d8 | 2421 | 2128-2478 | ~0% (noise) |

That split is the proof: only the per-file open/read/write steps collapse, while
the compute-bound steps are unchanged. It is not a general "everything got
faster" effect.

## The mechanism

`QuickBuildSessionManager.daemonConfig` sets
`outDir = File(layout.projectRoot, ".androidide/quickbuild/out")`, and
`layout.projectRoot` is under `/storage/emulated/0/CodeOnTheGoProjects/`. So
`classes/`, `ic/`, `cp-snap/`, `opened-classes/` and `dex/` all sit on emulated
storage — a FUSE view where every open/write/mkdir is a userspace round trip.

Same 464 files / 1.46 MB / 40 dirs, `cp -r`, best of 3 `[measured on a56]`:

| filesystem | time |
|---|---|
| `/data/local/tmp` (app-side ext4/f2fs) | **192 ms** |
| `/storage/emulated/0` (emulated, FUSE) | **9985 ms** |

`DexTool.openClasses` `deleteRecursively()`s the strip mirror and rewrites every
class — changed or not — once per build, which is why strip alone was 4.7-5.5 s.
The deploy policy and both tree walks pay the same tax on reads.

## Why Quick Build lost to Gradle, in order of size

1. **A filesystem penalty Gradle avoids.** Gradle's build dir is on the same
   storage, but its incremental tasks rewrite only changed classes; Quick Build's
   strip pass rewrites **all 464** every build and the deploy policy re-reads
   **323**. Quick Build turned a one-line edit into a whole-module I/O pass.
2. **javac is not incremental** — all 218 `.java` sources through a fresh
   `StandardJavaFileManager` every build, 2.1-4.2 s regardless of the edit.
3. **d8 is not incremental** — all 464 classes dexed from scratch, 2.1-4.6 s,
   where Gradle dexes per-class and merges.
4. For `03-java-abi-change` only: a moved Java ABI forces a full 74-file Kotlin
   recompile (14.9 s), by the deliberately blunt rule in
   `IncrementalCompiler.kotlinFilesToCompile`. This edit stays below 1x even
   after fix 1.

## Two hypotheses this killed

Worth recording, because both were plausible from code reading alone and both
were wrong:

- **"The Java-ABI gate fails open, silently recompiling all Kotlin on a Java
  edit."** The gate is healthy: a Java body edit measured `nKotlinToCompile=0`
  `[measured on a56]`. The 74-file Kotlin recompile is real but only on a genuine
  ABI change, which is the documented design.
- **"Non-incremental dexing is the dominant cost."** Half right. Dex *is* 48-60%
  of the edit, but most of that was the strip pass's file I/O, not dexing
  compute — strip fell 20x when only the filesystem changed. d8 itself remains
  3.7-3.9 s of the post-fix 8.1 s, which is fix 3, not fix 1.

## Correction to `incremental-javac-design.md`

Its premise is half right and its framing is wrong.

- **Right:** javac really does recompile all 218 sources every edit, costing
  2.1-4.2 s per warm edit `[measured on a56]` — its host-curve extrapolation of
  "~3 s per edit" was a good prediction.
- **Wrong:** it calls javac "the bottleneck." javac is **19-27%** of a warm edit
  before the filesystem fix and 26-35% after. The note only looked *inside*
  `compileMs` — where javac is 55-61% on the small Java apps it sampled — and
  `compileMs` in those c107 rows **excludes dex**. The dex half was the larger
  half, and the filesystem cost underneath both halves was invisible to every
  field it had.
- Its device-scaling factor was also off: it inferred ~40x host from 4-file c107
  rows; the A56 measures **7-14x** at N=218 `[inferred]`.

Its options A+B remain worth doing — they are just fix 2, not fix 1.

## Ranked fixes

1. **Move the daemon scratch tree off emulated storage.** Measured **-45%** on
   every warm sora edit and on medium-kotlin. Largest payoff, smallest diff (the
   experiment was 8 lines). **Not committed**, because shipping it needs three
   things this session did not build: a cleanup policy for stale per-project work
   dirs (they would otherwise accumulate in app-private storage forever, which
   matters on the 1.5 GB tier), a check of the rebaseline/teardown paths that may
   assume the out dir sits under the project root, and unit tests. The
   experimental patch keyed a scratch dir on `hashCode()` of the project path —
   good enough to measure, not to ship.
2. **Incremental javac** (the design note's options A + B). 2.1-4.2 s per edit,
   ~30% of what remains after fix 1.
3. **Incremental dexing.** 2.1-4.6 s per edit — the largest remaining item on a
   Java body edit after fix 1 (3.7-3.9 s of 8.1 s). D8 supports per-class output
   plus a merge step. Needs care about merge correctness under the never-stale
   invariant.
4. **Narrow the "Java ABI moved -> recompile all Kotlin" rule.** 14.9 s of the
   22.9 s ABI-change edit; moves only that edit class, but it is the worst one
   left.
5. **Stop re-stripping unchanged classes.** Largely subsumed by fix 1 (strip
   falls to 0.17-0.33 s off emulated storage), but still the right shape: strip
   is a pure function of the input bytes.

Incidental: **daemon IPC is free** — host-observed RPC minus daemon-observed
duration is 20-60 ms on multi-second calls `[measured on a56]`.

## The analytics lesson

The shipped fields (`kotlinMs`, `javacMs`, `stripMs`, `d8Ms`) sum to about
**half** of a warm edit, and the dominant cost lived entirely in the unmeasured
half. That is how a careful design note reached a wrong conclusion from real
device data. The durable fix is not more fields but an explicit **unaccounted
residual** in the analytics event: when a future change adds an expensive
un-timed step, the residual grows and the next reader sees it instead of
misattributing the cost. Tracked separately; see the analytics task.

## Limits of these numbers

- n=1 for variant A, n=2 for variant B on sora; n=1 for medium-kotlin per
  variant. The effect is far outside the run-to-run spread and has an independent
  mechanism measurement behind it, but these are not distributions.
- d8 is the noisiest step (2.1-4.6 s across otherwise-identical runs) — do not
  read small d8 deltas as signal.
- The 11909 ms standard-build reference was measured 2026-07-25 on CoGo
  `C-d-0725-0049`; it is a cross-version comparison.
- A56 only. The c107 is 4-13x slower and was not re-measured; the filesystem
  finding should be expected to reproduce there but is `[inferred]` until run.

## Scope note

This was never a v1 blocker: on the same A56, Quick Build is a median **2.5x
faster** than an incremental standard build across 21 apps `[measured on a56]`.
Fix 1 widens the set of projects Quick Build helps — and, being a ~45% win on
*every* app measured including the small one, it is not just a large-project fix.
