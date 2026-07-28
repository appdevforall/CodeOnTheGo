# Information: How the Quick Build speedup was measured, and which number to quote

## Summary

- Warm Quick Build beats a warm incremental standard build on most of the corpus:
  **~2.3x on the A56, ~2.9x on the C107**. It loses on exactly two apps per device.
- What that number covers:
  - the inner loop only — one warm save-to-live against that same app's median
    incremental standard build, on the same device;
  - 21 apps on the A56 and 18 on the C107, out of a 30-app / 97-edit corpus;
  - not covered: setup cost, rebaseline, the CoGo editor's own keystroke-to-disk
    latency, and correctness of the reloaded app.
- What makes the number non-obvious:
  - Three defensible readings of the same rows disagree by enough to matter
    (2.25x to 2.75x on the A56). The common error is pairing one reading's value
    with another reading's denominator.
  - Quick Build is **~1.3x slower than a first standard build** to reach a working
    session; it repays that in roughly four to six edits.
  - A per-edit attribution bug (fixed 2026-07-26) makes every per-edit and
    per-edit-class claim unusable until the affected sweeps are re-run.
  - Below 3.6 GB of RAM nothing builds at all by either route, which makes that
    tier a standard-build problem rather than a Quick Build one.
- **Takeaway: quote the per-app reading — ~2.3x on the A56 (21 apps) and ~2.9x on
  the C107 (18 apps) — and always quote it with its denominator.** The circulated
  "2.5x on the A56" is the per-edit reading; the circulated "2.7x on the C107"
  does not reproduce under any reading.

## How to read this page

- **Status:** the corpus sweeps are complete and the headline holds; a per-edit
  attribution bug (fixed 2026-07-26) means the affected sweeps have not been
  re-run, so per-edit and per-edit-class claims are not yet trustworthy.
- **Provenance tags are mandatory:**
  - `[measured on a56]` = Samsung A56 (8 GB class)
  - `[measured on c107]` = C107 (3.6 GB)
  - `[measured on itel]` = itel A667L (1.9 GB)
  - `[measured on Q8]` = incar Q8 (1.46 GB)
  - `[measured on host]` = Mac Mini
  - `[inferred]` / `[unmeasured]` mean what they say
  - Untagged prose is method description, not a result.
- **Evidence:** `corpus/results/*/` in the `test_app_corpus` repo, flattened into
  `corpus/results/analysis/{edits,apps}.csv` by `harness/export_csv.py`. Every
  number below was recomputed from those two tables on 2026-07-28; the recompute
  is `[measured on host]` over device data.

## Which number to quote, and why the circulated ones differ

All three readings come from the same rows `[measured on a56]` / `[measured on c107]`:

| reading | A56 | C107 |
|---|---|---|
| **per-app** (median across apps of each app's median warm edit ratio) | **2.25x, 21 apps** | **2.89x, 18 apps** |
| per-edit (median across warm edit keys) | 2.48x, 55 keys | 3.32x, 49 keys |
| pooled (median warm save-to-live vs median incremental standard, each taken device-wide) | 2.75x | 3.18x |

- The number to quote is the **per-app** reading, which weights each app once:
  ~2.3x on the A56 and ~2.9x on the C107, with its denominator — *21 apps on the
  A56, 18 on the C107*.
- The circulated **"2.5x on the A56"** is a different reading — per **edit key**,
  not per app. Both are defensible; they disagree by enough to matter, and the
  common error is to pair one reading's value with the other's denominator
  ("2.5x across 21 apps"), which is neither.
- The circulated **"2.7x on the C107" does not reproduce** under any of the
  readings above.
  - The nearest is the per-app calculation *without* collapsing repeats first,
    which gives 2.75x — a defensible variant, but not the default, because
    collapsing stops an app with many repeats of one edit from dominating its own
    median.
  - The C107 number to quote is **2.89x**.
  - An earlier revision of this page put the per-app C107 figure at 2.49x; that
    value does not reproduce either, and 2.89x replaces it.
  - See [`low-spec-devices.md`](low-spec-devices.md) for the C107 in depth — and
    note that a third of the C107's measured edits are attribution-suspect, so
    every C107 per-edit number carries the caveat in "How much of this to believe".

The exact recipe, so any of the three can be reproduced. This is
`harness/gen_artifact.py`'s method, and it is the one all three rows above use:

- collapse repeats of the same `(app, editId, device)` to their median;
- drop `05-manifest` (those edits never reached the watcher) and any save-to-live
  outside 200 ms - 60 s;
- keep keys whose lowest `editIndex` is > 1;
- join each key to the median `stdIncrementalMs` for its own `(app, device)`.

## Where the win is biggest, and the two apps that lose

Per-app spread is wide and the ranking is stable across devices
`[measured on a56, c107]`:

| | A56 | C107 |
|---|---|---|
| best | antennapod-model 4.5x, mixed-lang-cyclic 4.5x, resources-heavy 4.3x | antennapod-model 11.0x, kiss 9.1x, hello-kotlin 6.2x |
| median app | medium-kotlin 2.25x (11th of 21) | findroid 3.26x / mixed-lang-cyclic 2.52x (9th and 10th of 18, so the median is their mean, 2.89x) |
| slower than standard | sora-editor-full 2.7x slower, assets-app 1.7x slower | assets-app 1.2x slower, native-app 1.6x slower |

- Exactly two apps per device sit below 1x, which is what "loses on two apps" in
  the summary means.
- `readyou` is **not** one of them: it measures 1.20x on the A56 — Quick Build
  wins on its median, though on only two warm keys that disagree 5x (0.39x and
  2.02x), so its "median" is really the mean of two numbers.

The C107 wins are larger because its standard builds are much slower while the
Quick Build hot loop degrades less steeply `[measured on c107]`:

| C107 figure | value | reading |
|---|---|---|
| median incremental standard build | 26.6 s | median over apps of each app's own median |
| median cold standard build | 133 s | median over apps of each app's own median |
| median warm save-to-live (hot loop) | 10.1 s | median over apps of each app's own median |
| same hot loop, over warm edit *keys* | 8.0 s | |
| the widely-quoted "10.3 s" | 10.3 s | row-level median over all measured edits, cold first edits included |

The three hot-loop readings differ by 30%, so say which one you mean.

The `sora-editor-full` loss is root-caused, and the corpus number overstates it
`[measured on a56]`:

- a dedicated instrumented re-run measures a warm edit at 14.7 s against an
  11.9 s standard build (0.81x);
- moving the daemon scratch tree off FUSE storage takes it to 8.1 s (1.47x,
  winning);
- the corpus's 32 s median for that app is the average of two runs of the same
  edit that disagree 3.2x — see credibility, below;
- detail: [`sora-slow-path-gap.md`](sora-slow-path-gap.md) and
  [`perf-roadmap.md`](perf-roadmap.md).

## What a run measures, and what it leaves out

- A **run** is one unattended sweep of the corpus against one device
  (`harness/run_e2e_bench.py`). For each app it:
  - pushes a wrapped project;
  - opens it in CoGo through a flag-gated bench Activity that auto-starts a Quick
    Build session;
  - waits for Ready;
  - launches the test app;
  - applies that app's prepared edits one at a time.
  - Every enumerated edit ends as `MEASURED` or as a named `GAP` — a silent skip
    is treated as a harness bug.
- The **corpus** is 30 apps and 97 edits:
  - synthetic shapes built for this work (`hello-java`, `medium-kotlin`,
    `fanout-kotlin`, `mixed-lang-cyclic`, `resources-heavy`);
  - plus real open-source apps pinned at a sha (`kiss`, `antennapod-model`,
    `seal`, `readyou`, `findroid`, `sora-editor-full`, `streetcomplete-lib`, ...);
  - an **edit** is a numbered directory under `corpus/apps/<app>/edits/` holding
    replacement file contents plus a `meta.json` declaring its `editClass`, the
    files it replaces, and the route it should take;
  - there are 49 distinct edit classes, so most are represented by only one or
    two edits.
- The **measured span** is CoGo's own `reload_timeline` event: save-to-live is
  `reloadLive - trigger`, from `elapsedRealtime` stamps emitted by the device.
  - The harness never computes the headline from host wall clock, so host/device
    clock skew cannot enter it.
  - Not in that span: the CoGo editor itself (an `adb push` stands in for a save,
    so keystroke-to-disk latency is `[unmeasured]`), anything after `reloadLive`,
    and correctness — the sweep scores latency, not whether the reloaded app
    behaves correctly.
- The **standard-build comparison** is a separate bench kind
  (`harness/run_stdbuild_bench.py`) reading `standard_build_finished.durationMs`.
  - Each app is compared against **its own** incremental-standard median on the
    **same** device, joined on (app, device).
  - Only warm saves count (`editIndex > 1`): the first code edit of a pre-seed
    session paid a ~12 s cold-kotlinc warm-up that today's background seed hides,
    so including it would understate shipped behaviour.
  - Repeated measurements of the same edit are collapsed to their median, and
    values outside a 200 ms - 60 s plausibility window are dropped.

## Nothing builds below 3.6 GB, by either route

| device | RAM | role |
|---|---|---|
| Samsung A56 | 8 GB class | mid-tier reference; most sweeps run here |
| C107 | 3.6 GB | the low-end tier that still works |
| itel A667L | 1.9 GB | the tier that does not |
| incar Q8 | 1.46 GB | below the floor before a build starts |

- The A56 is not the target device; it is the one fast enough to iterate on. The
  C107 is the tier the product is actually for, which is why its numbers are
  reported alongside rather than as a footnote.
- The floor finding is the load-bearing result from the bottom two
  (`corpus/results/analysis/c107-lowend-report-2026-07-25.md`): **no on-device
  Gradle build of any kind completes at 1.9 GB** — not Quick Build provisioning,
  not a plain standard build `[measured on itel]`.
  - A `hello-java` build taking 82 s on the C107 did not finish in 900 s on a
    debloated, screen-off itel, with ~8.8 min of that in Gradle startup and
    configuration alone.
  - At 1.46 GB the device is below the floor at idle: 795 MB available with
    ~558 MB already in zram swap on CoGo's onboarding screen, before any build
    work `[measured on Q8]`.
  - Debloating and the Metaspace fix that rescued the C107 both failed to move
    the 1.9 GB outcome.
- So the 1.9 GB tier is a **standard-build** problem: the wall is the Gradle
  daemon that provisioning and a normal build share, not the Quick Build hot
  loop, which is a much smaller runtime.
- Whether a prebaked baseline that skips the on-device Gradle build would let the
  hot loop run at 1.9 GB is `[inferred]` and untested — the one lever that could
  move the floor.

## What Quick Build costs outside the inner loop

The speedup above is the inner loop only. Three costs sit outside it.

**Setup is slower than a first standard build**, per app, median
`[measured on a56]` / `[measured on c107]`:

| | A56 | C107 |
|---|---|---|
| Gradle sync (shared by both paths) | 29 s | 147 s |
| then: cold standard build | 29 s | 133 s |
| then: Quick Build setup to Ready | 47 s | 198 s |
| total, standard path | 58 s | 277 s |
| total, Quick Build path | 75 s | 339 s |

- The first-run pathway is about **1.3x slower** than getting a first standard
  build — 1.29x on the A56 across 25 apps, 1.25x on the C107 across 21.
- At the median per-edit saving (+2.7 s on the A56, +15.1 s on the C107, medians
  across the same apps) it repays that in roughly six edits on the A56 and four
  on the C107 `[inferred]`.
- But it is a real up-front cost, and it is paid before the user has seen
  anything run at all.

**Rebaseline is effectively unmeasured.**

- A structural change (manifest, gradle files, dependencies) drops out of the hot
  loop and rebuilds the baseline.
- The corpus recorded exactly **one** rebaseline per device, and neither
  succeeded (189 s on the A56, 215 s on the C107) `[measured on a56, c107]`.
- That is not a distribution and not a success rate.

**The marginal-setup gap is one app, not five.**

- 5 of 30 apps never reached Ready on the A56 (`compose-kotlin`, `notes`,
  `pedometer`, `qr-scanner`, `sudoku`) `[measured on a56]` — but four of those
  five also fail CoGo's *standard* build on the same device, so they are apps
  CoGo cannot build at all.
- Only `notes` builds fine by the standard route and still never reached a Quick
  Build session. The framing that reached the team overstated this.
- On the C107, 9 of 30 never reached Ready and none of those nine build by the
  standard route either `[measured on c107]`.

**A caveat that travels with every "median incremental standard build" figure on
this page:**

- the join takes each app's median `stdIncrementalMs` **without** checking
  `stdIncrementalOk`, so it includes durations recorded for standard builds that
  failed;
- on the C107 that is 9 of 30 apps and pulls the device-wide median from 26.6 s
  (21 apps that actually build) to 25.4 s;
- it changes no speedup, because none of those 9 apps has a joined Quick Build
  edit on either device — but quote 26.6 s when the claim is "what an incremental
  standard build costs on the C107", and 25.4 s only when reproducing the join.

**Coverage per sweep, for scale:**

- 70/92 and 78/97 edits measured on the A56;
- 70/97 and 68/97 on the C107, plus a third, partial C107 sweep at 17/20;
- the 101 GAP rows across all runs are dominated by provisioning failures ("test
  app install not confirmed", 46 rows) and per-edit `CompileError` /
  `DeployFailure` (35 rows).

## How much of this to believe

One defect makes a whole class of claims unusable, and a second already caused a
wrong conclusion in a design note.

### The per-edit attribution bug (task #57)

- The old driver joined an edit to its build by generation ordering — take the
  first `reload_timeline` whose generation exceeds the last one used.
- That holds only if one save produces exactly one build, and it does not: a
  multi-file edit pushes each file separately and re-fires the watcher, and even
  a single-file push has been observed firing twice 258 ms apart
  `[measured on a56]`.
- The surplus build takes the next free generation, so the *next* edit picks it
  up and every remaining edit of that app shifts by one.
- It is a cascade, not a one-row slip: on one sweep, 5 of `sora-editor-lib`'s 7
  rows moved when re-derived.

What that does and does not damage
(`docs/per-edit-attribution.md` in the corpus repo):

- The **population distribution barely moves** — re-deriving two runs with a
  persisted event feed changes the p50 by 1-6% (2384 -> 2355 ms; 11276 ->
  10843 ms) `[measured on a56, analysed on host]`. It is a shuffle within an app,
  not an inflation.
- **Coverage was overstated, which is worse.** On one run, 15 edits reported as
  MEASURED had no build of their own — the number filed against them belonged to a
  neighbour. Pre-fix runs count gaps as successes.
- **Every per-edit and per-edit-class claim is unsafe** until re-run, which is why
  no edit-class breakdown appears in this doc.

The regenerated tables carry an `attributionSuspect` column:

- **63 of 365 MEASURED rows are flagged** (17.3%), but that denominator flatters
  it: of the 303 rows that actually carry a save-to-live, 63 are flagged (20.8%);
- very unevenly spread — 11 of 148 on the A56 (7%) versus 52 of 155 on the C107
  (34%). A third of the C107's measured edits are suspect;
- filter `attributionSuspect = 1` before any per-edit pivot.

Filtering is not neutral, and not in the direction you would guess:

- suspect rows skew **slow** (C107 median 11.9 s suspect vs 9.6 s clean), so
  dropping them moves the per-app headline **up**: A56 2.25x -> 2.85x (still 21
  apps), C107 2.89x -> 3.80x, and the C107's sub-1x tail disappears;
- the bug is depressing the measured advantage, not inflating it;
- that is not licence to quote the filtered number — filtering removes a third of
  the C107's data, takes its denominator from 18 apps to 12, and the residue is
  not a random sample of it;
- the clean fix is re-running the sweeps.

### Repeat agreement is poor on the raw data and good only after filtering

State which, because the two answers are not close `[measured on a56, c107]`:

| | all rows | suspect rows dropped |
|---|---|---|
| warm edit keys | 104 | 88 |
| measured more than once | 93 | 59 |
| median spread between repeats | 1.10x | 1.04x |
| p90 spread | 4.19x | 1.29x |
| keys disagreeing by more than 3x | 11 | 3 |

- The filtered column is the one that was circulated as "repeat agreement is
  good", and it is the same filter this page says not to apply to headline
  numbers.
- On the raw data a p90 of 4.19x means one warm key in ten disagrees with itself
  by more than 4x.
- The three keys that survive filtering and still disagree by more than 3x:
  - `assets-app/03-asset-plus-code` (8.6x: 1.4 s vs 12.1 s)
  - `sora-editor-lib/02-sample-app-ui` (4.8x)
  - `sora-editor-full/02-kotlin-body-edit` (3.2x: 53.0 s vs 16.5 s)
- Two of those three apps *are* the "Quick Build loses" tail on the A56. So the
  least reproducible part of the dataset is the part the loss story rests on, and
  the suspect flag does not catch it.
- The other eight (worst: `mixed-lang-cyclic/02-java-method-body` on the C107,
  26x — 1.8 s vs 47.4 s) are flagged suspect, which is the flag working.

### Only 13 of 466 rows carry per-step timings, all from one C107 run

- The `kotlinMs` / `javacMs` / `stripMs` / `d8Ms` columns exist on paper for the
  whole corpus and are populated almost nowhere.
- `incremental-javac-design.md` drew its "javac is the bottleneck" conclusion by
  extrapolating from four of those rows.
- A dedicated A56 deep-dive later showed javac is 19-27% of a warm edit and the
  dominant cost was filesystem I/O none of the shipped fields could see
  `[measured on a56]`.
- Full correction in [`sora-slow-path-gap.md`](sora-slow-path-gap.md).

### What survives all of this

- the device-wide distributions and per-app medians (many rows, barely move under
  re-derivation, ranking reproduces across two devices and two sweeps each);
- the floor finding (build outcomes and memory profiles, not per-edit joins);
- the setup-cost comparison (from `apps.csv` state stamps, which the bug does not
  touch);
- the `sora-editor-full` regression and its root cause, measured by a separate
  instrumented run.

### What needs re-running before it can be quoted

- any per-edit-class breakdown;
- first-edit / cold-compile costs (`firstEditSaveToLiveMs`), precisely where a
  fan-out splits a cold compile in two and reports the smaller half;
- multi-file edit costs, reported from a partial build;
- the two loss-tail apps on the A56, whose repeat spread is 3-9x.

## Still owed

- **Re-run the affected e2e sweeps** on the fixed driver (task #82). Everything in
  the "needs re-running" list unblocks on this one job.
- **Re-onboard the C107** — its bootstrap was wiped — and run a rebaseline bench
  there (task #74). Rebaseline cost is currently one failed sample per device.
- **The Oppo device** has never been benchmarked; it needs a release-CoGo
  reinstall first (task #45, blocked on David).
- **The 1.9 GB hot-loop question:** whether a prebaked baseline lets the hot loop
  run where the Gradle setup build cannot. Untested, and the only identified path
  below the 3.6 GB floor.
- **Standard-build FUSE exposure** (task #106). Project build directories sit on
  the same slow filesystem the Quick Build scratch tree does; how much a standard
  build pays for it is `[unmeasured]`, and it would change the baseline every
  number here is measured against. See
  [`docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md).
