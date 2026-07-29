# Information: How Quick Build performs on real apps, and what it trades away

## Summary

- Quick Build is a new fast path for the on-device edit loop: a save recompiles only what changed and hot-reloads an already-running test app instead of running a Gradle build. The benchmark exists to answer what the design could not — is it actually faster than CoGo's standard incremental build, on real apps, on the hardware we ship to, and where does it stop working.
- It is measured against the standard build on the same device, over a corpus of 30 apps and 97 scripted edits, on two device tiers: the Samsung A56 (8 GB class) and the C107 (3.6 GB).
- **In the inner loop it wins: a warm save is 3.45x faster than the standard incremental build of the same edit** — median over 78 edits across 23 apps on the A56 `[measured on a56]`.
- **The slower the device, the bigger the win.** On the 19 edits measured on both tiers, the C107's speedup exceeds the A56's on **19 of 19**, by a median of 1.77x `[measured on a56]` `[measured on c107]`.
- Every tradeoff sits outside the inner loop:
  - reaching a first working session costs a marginal 24 s on the A56 and 91 s on the C107, repaid after about 7 edits `[measured on a56]` `[measured on c107]`;
  - a structural change (manifest, gradle files, dependencies) drops out of the fast path and rebaselines, which costs 11-14 s cold and ~5 s warm on the A56 and is `[unmeasured]` on the C107;
  - below 3.6 GB of RAM nothing builds at all by either route, which makes the bottom two tiers a standard-build problem rather than a Quick Build one `[measured on itel]` `[measured on Q8]`.
- It loses on **2 of 78 edits**, both on `sora-editor-full`, the largest app in the corpus.

## How to read this page

Provenance tags mark where every number came from, and never move:

- `[measured on a56]` — Samsung A56 (8 GB class)
- `[measured on c107]` — C107 (3.6 GB)
- `[measured on itel]` — itel A667L (1.9 GB)
- `[measured on Q8]` — incar Q8 (1.46 GB)
- `[measured on host]` — Mac Mini
- `[inferred]` / `[unmeasured]` mean what they say
- untagged prose is method description, not a result

Evidence: `corpus/results/*/` in the benchmark repo, flattened into `corpus/results/analysis/{edits,apps}.csv` by `harness/export_csv.py`. Every figure below is recomputed from those two tables; the recompute is `[measured on host]` over device data.

### The basis of every ratio on this page

A speedup here is always **one warm Quick Build save divided by the standard incremental build of the same edit, on the same app, the same device, and the same CoGo build**, and the headline is the median of those per-edit ratios across the corpus. Five filters enforce it, and each one changes the answer:

| filter | what it excludes | why |
| --- | --- | --- |
| One CoGo build | every build but `C-d-0728-1154` | Quick Build changed daily; a ratio spanning builds compares two products |
| One measurement regime | all but the latest standard-build sweep per device | the standard-build harness changed mid-corpus; the two regimes disagree by a median 1.35x |
| Successful standard builds | `stdIncrementalOk` false | a failed build's duration is a time-to-failure, not a denominator |
| Edit-matched denominators | rows with no standard build of that exact edit | dividing by a different edit's build compares two different amounts of work |
| Warm saves | nothing — the warm-up makes every measured save warm | a cold first compile is a different quantity |

The median is taken over **edits, not apps**. An app's edits span change categories, so any per-app figure mixes a method-body edit with an ABI change under one number.

## What the corpus is: 30 apps, 97 edits, replayed identically

A run is one unattended sweep of the whole corpus against one device (`harness/run_e2e_bench.py`). For each app it:

- pushes a wrapped project to the device;
- opens it in CoGo through a flag-gated bench Activity that auto-starts a Quick Build session;
- waits for Ready, then launches the test app;
- applies one **discarded warm-up edit**, awaits its build, and reverts it, so the one-time cold-compiler cost is spent outside the measured set;
- applies that app's prepared edits one at a time;
- records every enumerated edit as either `MEASURED` or a named `GAP` — a silent skip is treated as a harness bug, not a missing row.

What is in the corpus:

- five synthetic shapes built for this work — `hello-java`, `medium-kotlin`, `fanout-kotlin`, `mixed-lang-cyclic`, `resources-heavy`;
- the rest are real open-source Android apps pinned at a sha — `kiss`, `antennapod-model`, `seal`, `readyou`, `findroid`, `sora-editor-full`, `streetcomplete-lib`, and others;
- an edit is a numbered directory under `corpus/apps/<app>/edits/` holding replacement file contents plus a `meta.json` declaring its edit class, the files it replaces, and the route it should take;
- the same edit is replayed byte-identically on every device, so devices are comparable to each other;
- there are 49 distinct edit classes, so most classes are represented by only one or two edits.

Two device tiers carry the story, and two more mark the floor:

| device      | RAM        | role                                     |
| ----------- | ---------- | ---------------------------------------- |
| Samsung A56 | 8 GB class | mid-tier reference; most sweeps run here |
| C107        | 3.6 GB     | the low-end tier that still works        |
| itel A667L  | 1.9 GB     | the tier that does not                   |
| incar Q8    | 1.46 GB    | below the floor before a build starts    |

The A56 is not the target device; it is the one fast enough to iterate on. The C107 is the tier the product is actually for, which is why its numbers are reported alongside rather than in a footnote.

## What is timed, and what it is compared against

- The measured span is CoGo's own `reload_timeline` event: save-to-live is `reloadLive - trigger`, from `elapsedRealtime` stamps emitted by the device. The headline is never computed from host wall clock, so host/device clock skew cannot enter it.
- The standard-build comparison is a separate bench kind (`harness/run_stdbuild_bench.py`) reading `standard_build_finished.durationMs`. It applies the app's edits cumulatively and measures the incremental build of **every** edit, which is what makes an edit-matched denominator possible.
- Repeats of the same (app, edit, device) within a run collapse to their median; across runs, the most recent run that measured a key supersedes earlier ones rather than blending with them.
- Values outside a 200 ms - 240 s plausibility window are dropped. The ceiling only excludes the absurd — a java-ABI change on the largest corpus app legitimately takes ~100 s on the C107.
- `05-manifest` edits are excluded because a manifest change is a **structural** change that deliberately leaves the fast path, so its cost is a different quantity from an inner-loop save. Folding a structural rebuild into a sub-second warm-edit median would describe neither.

Three things are deliberately out of the timed span:

- the CoGo editor itself — an `adb push` stands in for a save, so keystroke-to-disk latency is `[unmeasured]`;
- anything after `reloadLive`;
- correctness — the sweep scores latency, not whether the reloaded app behaves correctly.

## Every unmeasured edit is a named gap, and runs are gated while they run

A benchmark is only worth as much as its accounting of what it failed to measure, so the harness is built so that a missing measurement cannot pass as a smaller successful run.

- Every enumerated edit ends as `MEASURED` or as a `GAP` **carrying its reason** — an app that could not provision records the resolution error that stopped it. A silent skip is treated as a harness bug.
- Each app also carries a named gap list, and every run reports `N measured / N gaps / N total` up front. An interrupted sweep therefore reads as exactly what it is.
- **Contamination is tracked separately from gaps**, because a measurement can exist and still not be trustworthy. The harness records whether an install dialog or a Play Protect prompt appeared, whether the build queue had quiesced before the edit landed, and how many builds were observed during a single edit.
- One derived check catches mis-attribution: if a recorded build span is longer than the wall-clock window the edit happened in, that build's watcher must have fired before the edit was saved, so the timing cannot belong to that edit. Those rows are flagged and filtered rather than used.

Runs are also validated **while in flight** (`harness/validate_run.py`). The gate checks that the CoGo build is pinned, that the warm-up ran and reverted, that the warm-up stayed out of the measured set, that per-stage timings are present, that the flagged-row rate is under a limit, and that timings fall inside the plausibility window. Its exit status stops the run, so a broken sweep dies in minutes instead of after a night.

## The win depends on what you change

`[measured on a56]` `[measured on c107]`. The split is on what Quick Build has to do: a change inside a method body recompiles one file, while a change to a declaration other files can see forces its dependents to recompile.

| category | A56 n | A56 median | A56 range | C107 n | C107 median |
| --- | --- | --- | --- | --- | --- |
| Code: body only | 26 | 3.34x | 0.80-22.65x | 7 | 6.20x |
| Code: API/ABI change | 39 | 3.23x | 0.50-12.20x | 8 | 10.85x |
| Resources | 9 | 4.44x | 3.06-5.49x | 1 | 6.29x |
| Assets | 2 | 28.65x | 16.32-40.99x | 2 | 129.30x |
| Mixed (code + resources/assets) | 2 | 6.93x | 3.24-10.63x | 1 | 18.84x |

- **Assets are a different kind of win.** An asset edit needs no compilation at all, so Quick Build finishes in ~0.08 s while a standard incremental build still runs a full Gradle build. The ratio is real, but it measures "Quick Build skips the build entirely", not a compiler speedup.
- **An ABI change costs about the same as a body edit at the median, but its spread is far wider.** The cost is set by how many dependents must recompile — near-zero on a small app, large on a big one.
- **Resources, Mixed, and every C107 cell are thin** (n=1 to 8). They show direction, not a distribution.

## The one app Quick Build loses on

`sora-editor-full`, the largest app in the corpus (292 sources), on the A56 `[measured on a56]`:

| edit | QB | standard incremental | ratio |
| --- | --- | --- | --- |
| `01-java-body-edit` | 11.0 s | 43.3 s | 3.94x |
| `02-kotlin-body-edit` | 14.2 s | 11.4 s | **0.80x** |
| `03-java-abi-change` | 30.9 s | 15.3 s | **0.50x** |

- Those two are the only edits in the whole dataset slower than the standard build.
- The loss is partly root-caused: moving the daemon's scratch tree off FUSE-backed storage takes a comparable warm edit from 14.7 s to 8.1 s, so the storage work plausibly converts this app `[measured on a56]`.
- Detail: `corpus/results/20260728T172912Z-sora-deepdive/` and [`perf-roadmap.md`](perf-roadmap.md).

## The low tier gains more, because its standard builds degrade faster

The comparison holds the edit constant, over the 19 (app, edit) pairs measured on both devices `[measured on a56]` `[measured on c107]`:

| | value |
| --- | --- |
| C107 speedup exceeds A56 | **19 of 19 pairs** |
| median per-edit C107/A56 ratio | **1.77x** |
| same-pair A56 median | 5.21x |
| same-pair C107 median | 10.55x |

- Absolute costs behind that: the median matched edit takes 1.60 s by Quick Build against 4.94 s by standard build on the A56, and 3.79 s against 20.93 s on the C107.
- **The C107's own corpus-wide median is not reported**, because its latest sweep aborted alphabetically at app 8 of 30 when the device rebooted into a locked state. The 6 apps it reached are small and exclude `sora-editor-full`, so a median over them would read high and would not be comparable to the A56's 23-app figure. Holding the edit constant is what makes the tier claim safe.
- Per-tier depth, including where the C107's remaining latency goes, is in [`low-spec-devices.md`](low-spec-devices.md).

## Reaching a first working session costs more than a first standard build

Per app, median `[measured on a56]` `[measured on c107]`. Each cell is an independent per-app median, so the columns do not sum — a total is the median of per-app totals, not the sum of the medians above it.

|                                    | A56    | C107    |
| ---------------------------------- | ------ | ------- |
| Gradle sync (both paths pay it)    | 29.7 s | 151.1 s |
| then: cold standard build          | 17.8 s | 115.3 s |
| then: Quick Build setup to Ready   | 45.3 s | 203.6 s |
| marginal cost of a Quick Build session on an already-built project | 24.2 s | 91.1 s |

- **Payback is about 7 edits on the A56** — the marginal setup cost divided by the per-edit saving, median over 22 apps `[measured on a56]`. The C107 computes to about 5 edits but over only 6 apps, so treat it as indicative `[measured on c107]`.
- It is a real up-front cost, and it is paid before the user has seen anything run at all.
- On `sora-editor-full` the per-edit gain is negative, so it never pays back.

## Rebaseline is the one fallback, and only the A56 is measured

- A structural change — manifest, gradle files, dependencies — drops out of the hot loop and rebuilds the baseline.
- On the A56, five apps rebaseline cleanly, 10 of 10 attempts `[measured on a56]`:

| app | first rebaseline | warm repeat |
| --- | --- | --- |
| `hello-kotlin` | 13.3 s | 5.0 s |
| `medium-kotlin` | 13.1 s | 5.1 s |
| `multi-activity-kotlin` | 12.5 s | 4.7 s |
| `resources-heavy` | 10.7 s | 4.9 s |
| `sora-editor-lib` | 13.8 s | 5.4 s |

- Consistent within 1.3x across apps, and a warm repeat is roughly a third of the first — so the fallback costs about one cold standard build once, then about 5 s.
- **The C107 arm is `[unmeasured]`**: every app in it failed to provision, because `adb push` is denied against a device that has rebooted into a locked state.
- A rebaseline whose reinstall changes the app's bytes needs a human to confirm an OS install dialog, so install-bearing rebaselines are `[unmeasured]` on both tiers.

## Nothing builds below 3.6 GB, by either route

- The load-bearing result from the bottom two tiers: no on-device Gradle build of any kind completes at 1.9 GB — not Quick Build provisioning, not a plain standard build `[measured on itel]`.
- A `hello-java` build taking 82 s on the C107 did not finish in 900 s on a debloated, screen-off itel, with ~8.8 min of that in Gradle startup and configuration alone `[measured on itel]`.
- At 1.46 GB the device is below the floor at idle: 795 MB available with ~558 MB already in zram swap on CoGo's onboarding screen, before any build work `[measured on Q8]`.
- Debloating and the Metaspace fix that rescued the C107 both failed to move the 1.9 GB outcome `[measured on itel]`.
- So the 1.9 GB tier is a standard-build problem: the wall is the Gradle daemon that provisioning and a normal build share, not the Quick Build hot loop, which is a much smaller runtime.
- Whether a prebaked baseline that skips the on-device Gradle build would let the hot loop run at 1.9 GB is `[inferred]` and untested — the one identified lever that could move the floor.
- Full data and the per-device mechanism: [`low-spec-devices.md`](low-spec-devices.md).

## What this pass covered, and what it did not

- **23 of 30 apps yield A56 measurements.** Of the 7 that do not: 4 (`compose-kotlin`, `pedometer`, `qr-scanner`, `sudoku`) fail the plain standard Gradle build on both tiers and so are corpus-side rather than a Quick Build limit; 2 (`2048`, `ruler`) build by standard Gradle and reach a Quick Build session but fail at **deploy** on every edit; 1 (`notes`) declares `android:process=":remote"`, which Quick Build detects and rejects with an actionable message, as designed.
- So "23 of 30" is not a statement that Quick Build failed on 7 apps.
- **The C107 covers 6 apps in this regime**, for the reason given above. Its earlier, broader sweep is not combined with it.

## Methodology: exactly what was run, and how

Every script lives in `harness/` in the benchmark repo (`appdevforall/CodeOnTheGo-build-benchmark`). Its `README.md` carries the same recipe with full flags; this section records what produced *these* numbers so a reader can tell measurement from inference.

### The three arms, and why all three are needed

A speedup ratio needs a numerator and a denominator measured on the same CoGo build, the same device, and the same app. They come from different scripts, which is the single easiest thing to get wrong.

| arm            | script                    | produces                                                     |
| -------------- | ------------------------- | ------------------------------------------------------------ |
| Quick Build    | `run_e2e_bench.py`        | warm save-to-live per edit, plus compile/stage/apply and the kotlin/javac/strip/d8/aapt2 sub-steps |
| Standard build | `run_stdbuild_bench.py`   | cold standard build, warm **incremental** standard build for every edit, and marginal Quick Build setup |
| Fallback       | `run_rebaseline_bench.py` | rebaseline duration, via two `gradle.properties` appends with the install dialog hunted |

The e2e sweep alone cannot produce a ratio: it contains no standard-build timing at all.

### The pass behind this page (2026-07-29)

All sweeps ran on one pinned CoGo build, **`C-d-0728-1154`**:

- `run_e2e_bench.py` on the A56 (`RZGYC24640P`, plain adb) and on the C107 (`C107000001001112`, `ANDROID_ADB_SERVER_PORT=15037`), `--warmup` on, `--no-resume`.
- `run_stdbuild_bench.py` on both devices, same flags, measuring every edit.
- `run_rebaseline_bench.py` on both devices, five apps that reliably escalate.
- `--wrapped-dir` pointed at the **full 31-app wrapped corpus**. Passing the wrong one silently yields a handful of apps and reads as a device failure.

Then, host-side only:

```
python3 harness/export_csv.py
python3 harness/validate_run.py <rundir> --checkpoint 3 --wrapped-dir <wrapped>/<app>
python3 harness/gen_artifact.py --only-cogo C-d-0728-1154 --out web/qb-report-2026-07-29.html
```

`--only-cogo` is an allowlist: it states "this report *is* build X" and hard-fails if the build matches nothing. It pins the edit charts, setup cost, payback and rebaselines alike.

### Controls

- **One device at a time is preferred.** Concurrency does **not** measurably skew timings — save-to-live comes from CoGo's own device-side event feed, and the two devices share no compute; a same-build A/B on `service-app` put the concurrent and sequential numbers 1.02x apart. What it *does* cause is spurious `DeployFailure` gaps, because the shared host and adb path is what breaks. So concurrency buys wall-clock at the cost of gap-count accuracy, not timing accuracy.
- **Standard-build cross-check:** for any app that never yields a Quick Build measurement, the plain Gradle build is run on the same device. It separates "Quick Build cannot do this" from "this app does not build at all".
- **Mid-run gating:** `validate_run.py` checks attribution-suspect rate, warm-up leakage into measured edits, warm-up revert, and build pinning, so a bad sweep is killed in minutes rather than after a night of device time.

## Limitations

- **The standard incremental build is not reproducible.** The same (app, edit) measured 14.07 s and 3.64 s two minutes apart, a 3.87x spread, with roughly one build per app picking up ~8 s of unexplained cost `[measured on a56]`. The interference is one-directional, so the corpus median is robust while any single per-edit ratio is not. The prewarm, memory pressure, and a plain configuration-cache miss are all refuted as causes.
- **The C107 is a 6-app sample in this regime**, so it supports the paired tier comparison and not a corpus-wide low-end figure. More device time is owed.
- Correctness is out of scope. The sweep scores latency; nothing here says the reloaded app behaves like a fully built one.
- The editor's own keystroke-to-disk latency is `[unmeasured]` — the harness pushes files instead of typing.
- Rebaseline is measured only on the A56, and only for the case that does not reinstall.
- The two lowest tiers have only the floor result. Nothing about Quick Build's performance at 1.9 GB or below is measured, because nothing builds there.
- One farm device (the Oppo) has never been benchmarked at all.
- Project build directories sit on the same slow FUSE-backed filesystem the Quick Build scratch tree does, and how much a standard build pays for that is `[unmeasured]`. It would move the baseline every ratio here is measured against — see [`on-device-storage-performance.md`](../../docs/on-device-storage-performance.md).
- How often the fast path applies to real-world edits is a separate question this benchmark does not answer; it is a static commit survey, in [`commit-survey.md`](commit-survey.md).
- Known functional limits of the feature itself — the edit classes that always rebaseline, the API 28/29 resource path, cert-pinned services — are in [`README.md`](../README.md) under Known limitations, not here.
