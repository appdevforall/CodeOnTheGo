# Benchmarking Quick Build: what we measured, how, and what it says

Status: the corpus sweeps are complete and the headline holds; a per-edit
attribution bug (fixed 2026-07-26) means the affected sweeps have not been
re-run, so per-edit and per-edit-class claims are not yet trustworthy.

Provenance tags are mandatory. `[measured on a56]` = Samsung A56 (8 GB class),
`[measured on c107]` = C107 (3.6 GB), `[measured on itel]` = itel A667L
(1.9 GB), `[measured on Q8]` = incar Q8 (1.46 GB), `[measured on host]` = Mac
Mini, `[inferred]` / `[unmeasured]` mean what they say. Untagged prose is
method description, not a result.

Evidence: `corpus/results/*/` in the `test_app_corpus` repo, flattened into
`corpus/results/analysis/{edits,apps}.csv` by `harness/export_csv.py`. Every
number below was recomputed from those two tables on 2026-07-28; the recompute
is `[measured on host]` over device data.

## Headline

Warm Quick Build beats a warm incremental standard build on most of the corpus,
by roughly 2-3x on the A56 and 2.5-5x on the C107, and loses on two apps. It
costs more up front: reaching a working session takes ~1.3x as long as reaching
a first standard build. Below 3.6 GB of RAM nothing builds at all, by either
route, which makes that tier a standard-build problem rather than a Quick Build
one.

The number most often quoted, "2.5x on the A56", is one of three defensible
readings of the same data. They disagree by enough to matter, so this doc names
which is which rather than picking one silently.

## What a run measures

A **run** is one unattended sweep of the corpus against one device, driven by
`harness/run_e2e_bench.py`. For each app it pushes a wrapped project to the
device, opens it in CoGo through a flag-gated bench Activity that auto-starts a
Quick Build session, waits for the session to reach Ready, launches the built
test app, then applies that app's prepared edits one at a time. Every enumerated
edit ends as `MEASURED` or as a named `GAP` — a silent skip is treated as a
harness bug.

The **corpus** is 30 apps and 97 edits, a mix of synthetic shapes built for this
work (`hello-java`, `medium-kotlin`, `fanout-kotlin`, `mixed-lang-cyclic`,
`resources-heavy`) and real open-source apps pinned at a sha (`kiss`,
`antennapod-model`, `seal`, `readyou`, `findroid`, `sora-editor-full`,
`streetcomplete-lib`, ...). An **edit** is a numbered directory under
`corpus/apps/<app>/edits/` holding replacement file contents plus a `meta.json`
that declares its `editClass` (`method-body`, `signature-change`, `new-class`,
`resource-value`, `java-abi-change`, `asset-only`, ...), the files it replaces,
and the route it is expected to take. There are 49 distinct edit classes, so
most classes are represented by only one or two edits.

The **measured span** is CoGo's own `reload_timeline` event: four
`elapsedRealtime` stamps (`trigger`, `compileDone`, `deploySent`, `reloadLive`)
emitted by the device. Save-to-live is `reloadLive - trigger`. The harness never
computes the headline number from host wall clock — it reads the device's own
stamps — so host/device clock skew cannot enter it.

What is **not** in that span:

- The CoGo editor itself. The harness's `adb push` of a changed file is what
  fires the watcher, standing in for an editor save. Keystroke-to-file-on-disk
  latency inside the editor is `[unmeasured]`.
- Anything the user perceives after `reloadLive` — the frame actually rendering
  the new code.
- Correctness. The corpus declares a `behavioralMarker` per edit but the sweep
  scores latency, not whether the reloaded app behaves correctly. Correctness is
  covered by the unit and device test suites, not here.

The standard-build comparison is a separate bench kind
(`harness/run_stdbuild_bench.py`) driving CoGo's own standard build on the same
project and reading `standard_build_finished.durationMs`. Cold (from Run) and
warm incremental (a re-fire after one code edit) are recorded separately. Warm
incremental is the fair comparison to a Quick Build save-to-live; cold is not.

## The devices, and why those

| device | RAM | role |
|---|---|---|
| Samsung A56 | 8 GB class | the mid-tier reference; most sweeps run here |
| C107 | 3.6 GB | the low-end tier that still works |
| itel A667L | 1.9 GB | the tier that does not |
| incar Q8 | 1.46 GB | below the floor before a build starts |

The A56 is not the target device; it is the one fast enough to iterate on. The
C107 is the tier the product is actually for, which is why its numbers are
reported alongside rather than as a footnote.

The floor finding is the load-bearing result from the bottom two
(`corpus/results/analysis/c107-lowend-report-2026-07-25.md`):

- **No on-device Gradle build of any kind completes at 1.9 GB** — not Quick
  Build provisioning, not a plain standard build `[measured on itel]`. A
  `hello-java` build that takes 82 s on the C107 did not finish in 900 s on a
  debloated, screen-off itel; ~8.8 min of that was Gradle startup and
  configuration alone, with the heap pinned against its 616 MB RAM-scaled
  ceiling under SerialGC thrash.
- **1.46 GB is below the floor at idle** — 795 MB available and ~558 MB already
  in zram swap on CoGo's onboarding screen, before any build work
  `[measured on Q8]`.
- Debloating (16 packages disabled) and the Metaspace fix that rescued the C107
  both failed to move the 1.9 GB outcome `[measured on itel]`.

So the 1.9 GB tier is a **standard-build** problem. The wall is the Gradle
daemon that Quick Build provisioning and a normal build share, not the Quick
Build hot loop, which is a much smaller runtime. Whether a prebaked baseline
that skips the on-device Gradle build would let the hot loop run at 1.9 GB is
`[inferred]` and untested — it is the one lever that could move the floor.

## The headline comparison

Each app is compared against **its own** incremental-standard median on the
**same** device; the two sides come from different bench kinds, so they are
joined on (app, device). Only warm Quick Build saves count (`editIndex > 1`):
the first code edit of a pre-seed session paid a ~12 s cold-kotlinc warm-up that
today's background seed hides, so including it would understate the shipped
behaviour. Repeated measurements of the same edit are collapsed to their median,
and values outside a 200 ms - 60 s plausibility window are dropped as
mis-attributions.

Three readings, all from the same rows:

| reading | A56 | C107 |
|---|---|---|
| per-app (median across apps of each app's median warm edit) | 2.25x, 21 apps | 2.49x, 18 apps |
| per-edit (median across warm edit keys) | 2.48x, 55 keys | 3.32x, 49 keys |
| pooled (device-wide median save-to-live vs device-wide median incremental standard) | 2.27x | 2.48x |

`[measured on a56]` / `[measured on c107]`. The circulated "2.5x on the A56" is
the per-edit reading. The circulated "2.7x on the C107" does not reproduce from
today's tables under any of the three; the closest is 2.49x. Treat the per-app
reading as the headline — it weights each app once instead of letting the apps
with the most edits dominate — and quote it as **~2.3x on the A56, ~2.5x on the
C107**.

Per-app spread is wide and the ranking is stable across devices
`[measured on a56, c107]`:

| | A56 | C107 |
|---|---|---|
| best | antennapod-model 4.5x, resources-heavy 4.3x, kiss 4.2x | antennapod-model 11.0x, kiss 9.1x, hello-kotlin 6.2x |
| median app | medium-kotlin 2.25x | mixed-lang-cyclic 2.5x |
| slower than standard | sora-editor-full 2.7x slower, assets-app 1.9x slower, readyou 1.5x slower | assets-app 1.3x slower, native-app 1.6x slower |

The C107 wins are larger because its standard builds are much slower (25 s
median incremental, 133 s cold) while the Quick Build hot loop degrades less
steeply (10 s median) `[measured on c107]`.

The `sora-editor-full` loss is root-caused, and the corpus number overstates it:
a dedicated instrumented re-run measures a warm edit at 14.7 s against an 11.9 s
standard build (0.81x, i.e. 1.2x slower), and moving the daemon scratch tree off
FUSE storage takes it to 8.1 s (1.45x, winning) `[measured on a56]`. See
[`sora-slow-path-gap.md`](sora-slow-path-gap.md) and
[`perf-roadmap.md`](perf-roadmap.md). The corpus's 32 s median for that app is
the average of two runs of the same edit that disagree 3.2x — see the
credibility section.

## What Quick Build costs

The speedup above is the inner loop only. Three costs sit outside it.

**Setup is slower than a first standard build.** From project open to a usable
state, per app, median `[measured on a56]` / `[measured on c107]`:

| | A56 | C107 |
|---|---|---|
| Gradle sync (shared by both paths) | 29 s | 147 s |
| then: cold standard build | 29 s | 133 s |
| then: Quick Build setup to Ready | 47 s | 198 s |
| total, standard path | 58 s | 277 s |
| total, Quick Build path | 75 s | 339 s |

So Quick Build's first-run pathway is about **1.3x slower** than getting a first
standard build (25 apps on the A56, 21 on the C107). It repays that within a
handful of edits on the A56 and within two or three on the C107, but it is a
real up-front cost and it is paid before the user has seen anything run.

**Rebaseline is effectively unmeasured.** A structural change (manifest, gradle
files, dependencies) drops out of the hot loop and rebuilds the baseline. The
corpus recorded exactly **one** rebaseline per device, and neither succeeded
(189 s on the A56, 215 s on the C107) `[measured on a56, c107]`. That is not a
distribution and not a success rate; the rebaseline path's cost is `[unmeasured]`.

**The marginal-setup gap: 5 of 30 apps never reached Ready on the A56**
(`compose-kotlin`, `notes`, `pedometer`, `qr-scanner`, `sudoku`)
`[measured on a56]`. Four of those five also fail CoGo's *standard* build on the
same device, so they are not Quick Build gaps — they are apps CoGo cannot build
at all. Only `notes` builds fine by the standard route and still never reached a
Quick Build session. That is one genuine Quick-Build-only setup gap out of 30
apps, not five; the framing that reached the team overstated it. On the C107, 9
of 30 never reached Ready and none of those nine build by the standard route
either `[measured on c107]`.

Coverage per sweep, for scale: 70/92 and 78/97 edits measured on the A56; 70/97
and 68/97 on the C107. The 101 GAP rows across all runs are dominated by
provisioning failures ("test app install not confirmed", 46 rows) and per-edit
`CompileError` / `DeployFailure` (35 rows).

## How much of this to believe

Be blunt about this part: one defect makes a whole class of claims unusable, and
a second one already caused a wrong conclusion in a design note.

**The per-edit attribution bug (task #57).** The old driver joined an edit to
its build by generation ordering: take the first `reload_timeline` whose
generation exceeds the last one used. That holds only if one save produces
exactly one build, and it does not — a multi-file edit pushes each file
separately and re-fires the watcher, and even a single-file push has been
observed firing twice 258 ms apart `[measured on a56]`. The surplus build takes
the next free generation, so the *next* edit picks it up and every remaining
edit of that app shifts by one. It is a cascade, not a one-row slip: on one
sweep, 5 of `sora-editor-lib`'s 7 rows moved when re-derived.

What that does and does not damage
(`docs/per-edit-attribution.md` in the corpus repo):

- The **population distribution barely moves**. Re-deriving two runs with a
  persisted event feed changes the p50 by 1-6% (2384 -> 2355 ms; 11276 ->
  10843 ms) `[measured on a56, analysed on host]`. It is a shuffle within an
  app, not an inflation.
- **Coverage was overstated, which is worse.** On one run, 15 edits reported as
  MEASURED had no build of their own — the number filed against them belonged to
  a neighbour. Pre-fix runs count gaps as successes.
- **Every per-edit and per-edit-class claim is unsafe** until re-run. Edit
  classes are assigned per edit, so a shuffle moves timings between classes.
  This is why no edit-class breakdown appears in this doc.

The regenerated tables carry an `attributionSuspect` column. **63 of 365
MEASURED rows are flagged** (17.3%), but that denominator flatters it: of the
303 rows that actually carry a Quick Build save-to-live, 63 are flagged (20.8%),
and they are very unevenly spread — 11 of 148 on the A56 (7%) versus 52 of 155
on the C107 (34%). A third of the C107's measured edits are suspect. Filter
`attributionSuspect = 1` before any per-edit pivot.

Filtering is not neutral, and the direction is not the one you would guess.
Suspect rows skew **slow** (C107 median 11.9 s suspect vs 9.6 s clean), so
dropping them moves the per-app headline **up**: A56 2.25x -> 2.85x, C107 2.49x
-> 4.12x, and the C107's sub-1x tail disappears entirely. The bug is depressing
the measured advantage, not inflating it. That is not licence to quote the
filtered number — filtering removes a third of the C107's data and the residue
is not a random sample of it. The clean fix is re-running the sweeps.

**Repeat agreement is good except exactly where it matters.** 59 of 88 warm edit
keys were measured more than once; median spread between repeats is 1.04x and
p90 is 1.29x `[measured on a56, c107]`. But the three keys above 3x are
`assets-app/03-asset-plus-code` (8.6x: 1.4 s vs 12.1 s),
`sora-editor-lib/02-sample-app-ui` (4.8x) and
`sora-editor-full/02-kotlin-body-edit` (3.2x: 53.0 s vs 16.5 s) — and two of
those three apps *are* the "Quick Build loses" tail on the A56. None of them is
flagged suspect. So the least reproducible part of the dataset is the part the
loss story rests on, and the suspect flag does not catch it.

**Only 13 of 466 rows carry per-step timings, all from one C107 run.** The
`kotlinMs` / `javacMs` / `stripMs` / `d8Ms` columns exist on paper for the whole
corpus and are populated almost nowhere. `incremental-javac-design.md` drew its
"javac is the bottleneck" conclusion by extrapolating from four of those C107
rows; a dedicated A56 deep-dive later showed javac is 19-27% of a warm edit and
the dominant cost was filesystem I/O that none of the shipped fields could see
`[measured on a56]`. Full correction in
[`sora-slow-path-gap.md`](sora-slow-path-gap.md). The durable lesson is in
[`perf-roadmap.md`](perf-roadmap.md): the four shipped fields sum to about half
a warm edit, so the analytics event needs an explicit unaccounted residual, not
more fields.

**What survives all of this:**

- The device-wide distributions and the per-app medians. They are built from
  many rows, they barely move under re-derivation, and the ranking reproduces
  across two devices and two sweeps per device.
- The floor finding. It rests on build outcomes and memory profiles, not on
  per-edit joins.
- The setup-cost comparison. It comes from `apps.csv` state stamps, which the
  attribution bug does not touch.
- The `sora-editor-full` regression and its root cause, which were measured by a
  separate instrumented run, not by the corpus sweep.

**What needs re-running before it can be quoted:**

- Any per-edit-class breakdown ("resource edits cost X, signature changes cost
  Y").
- First-edit / cold-compile costs (`firstEditSaveToLiveMs`), which is precisely
  where a fan-out splits a cold compile in two and reports the smaller half.
- Multi-file edit costs, which were reported from a partial build.
- The two loss-tail apps on the A56, whose repeat spread is 3-9x.

## Still owed

- **Re-run the affected e2e sweeps** on the fixed driver (task #82). Everything
  in the "needs re-running" list above unblocks on this one job.
- **Re-onboard the C107** — its bootstrap was wiped — and run a rebaseline bench
  there (task #74). Rebaseline cost is currently one failed sample per device.
- **The Oppo device** has never been benchmarked; it needs a release-CoGo
  reinstall first (task #45, blocked on David).
- **The 1.9 GB hot-loop question.** Whether a prebaked baseline lets the Quick
  Build loop run where the Gradle setup build cannot is the only identified path
  below the 3.6 GB floor, and it is untested.
- **Standard-build FUSE exposure** (task #106). Project `build/` directories sit
  on the same slow filesystem the Quick Build scratch tree does; how much a
  standard build pays for it is `[unmeasured]`, and it would change the baseline
  every number here is measured against.
