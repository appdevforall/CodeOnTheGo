# Backlog Item - General Bug: Standard and Quick Build both work at 3.6 GB RAM but not at 1.9GB and below.

**Provenance**: `[measured on a56]` (Samsung A56, mid-tier), `[measured on c107]` (C107, 3.6 GB), `[measured on itel]` (itel A667L, 1.9 GB), `[measured on Q8]` (incar Q8, 1.46 GB), `[inferred]`, `[unmeasured]`.

**Primary evidence**: `corpus/results/analysis/c107-lowend-report-2026-07-25.md` in the benchmark repo — this page is the story, that report is the data. To run the farm devices, see [`low-spec-runbook.md`](low-spec-runbook.md).

## Summary

- Quick Build works on the C107 (3.6 GB): **~2.9x** faster than the standard build, saving **+15.1 s** on the median app's warm edit — a bigger absolute saving than the A56 gets.
- The floor sits between 1.9 GB and 3.6 GB:
  - C107, 3.6 GB — works
  - itel A667L, 1.9 GB — walled: never provisions, never finishes a build. The only app attempted there was **pure Java** (`hello-java`, 3 `.java` files, zero Kotlin), so the wall is not the cost of loading the Kotlin toolchain
  - incar Q8, 1.46 GB — already thrashing with CoGo open and idle, before anything starts building
- **The wall is not Quick Build's.** What fails at 1.9 GB is the standard Gradle build that provisions it; Quick Build adds no failures of its own on any tier we measured. No amount of Quick Build work moves the floor.
- The one lever that could `[inferred]`: serve provisioning without a full on-device Gradle build. The Quick Build hot loop is a much smaller runtime than the Gradle daemon that dies.
- **Decision: do we accept 3.6 GB as the supported minimum, or spike Gradle-free provisioning to try to reach 1.9 GB?**
  - Would put a device tier the mission targets back in reach — today nothing Quick Build can do gets there.
  - Two open items should close first; one of them may be a harness artefact.

## How this was tested

- Four devices against one 30-app corpus: A56 (8 GB) as reference, C107 3.6 GB, itel A667L 1.9 GB, incar Q8 1.46 GB.
- Each C107 app was run both ways — standard Gradle build (cold full Run, then incremental against a warm daemon) and Quick Build (setup to Ready, then warm save->live edits).
- Pass criteria: the standard build reaches `standard_build_finished`; Quick Build reaches Ready and lands a save->live edit.
- Speedup is the per-app median over 18 apps, repeated measurements of the same edit collapsed first — methodology owned by [`benchmarking.md`](benchmarking.md).
- C107 figures are post-fix: before commit `6d198f576` the low tiers capped the daemon at `MaxMetaspaceSize=192m` and `:app:assembleDebug` died in `OutOfMemoryError: Metaspace`; at 384m it builds green `[measured on c107]`.
- itel: **one app only** — `hello-java`, the pure-Java template. Stock runs, plus a debloat experiment — 16 packages disabled (Bryan-approved, snapshot kept, fully restored afterward), screen off, 900 s window `[measured on itel, debloated + screen-off; do not compare naively against stock runs]`.
- Q8: idle profile only, on CoGo's onboarding screen. No build attempted — its carousel ignores programmatic taps, so setup needs a human touching the screen.

## The floor, in one table

| Device     | RAM     | Quick Build                                             | Standard Gradle build                   | Verdict                                    |
| ---------- | ------- | ------------------------------------------------------- | --------------------------------------- | ------------------------------------------ |
| A56        | 8 GB    | works                                                   | works                                   | reference                                  |
| C107       | 3.6 GB  | reaches Ready on 21/30 apps; 68-70 of 97 edits measured | builds 21/30 apps, cold and incremental | **works** `[measured on c107]`             |
| itel A667L | 1.9 GB  | never provisions                                        | never finishes                          | **walled** `[measured on itel]`            |
| incar Q8   | 1.46 GB | not reached                                             | not reached                             | **below floor at idle** `[measured on Q8]` |

- The two C107 columns are the **same 21 apps**: every app that reaches Quick Build Ready also builds by the standard route, and the 9 that miss Ready are exactly the 9 CoGo cannot build there `[measured on c107]`.
- The 9 C107 misses are **not a device-tier limit** — they are the benchmark corpus failing to provision dependencies. Five die on two artifacts (`kotlinx-coroutines-core:1.8.1`, `kotlinx-serialization-core:1.6.3`) that were never resolvable offline, and the C107 had no network (`UnknownHostException: dl.google.com`); the other four fail to build on the A56 too. Root-caused since: those are KMP coordinates publishing no jvm artifact, so the corpus staged the `-jvm` module but never the root the build file declares — 8 of 30 apps were affected, now fixed host-side `[measured on host]`. **So "21 of 30" describes our corpus, not the C107 and not Quick Build.**
- On the itel, both Quick Build provisioning and a plain `:app:assembleDebug` die for the same reason: both run the same CoGo-sized Gradle daemon.

## The C107 wins by a bigger absolute margin than the A56

| Device | Speedup | Saving on the median app's warm edit |
| ------ | ------- | ------------------------------------ |
| A56    | ~2.3x   | +2.7 s                               |
| C107   | ~2.9x   | **+15.1 s**                          |

- **34% of the C107's measured edits are attribution-suspect**, against 7% on the A56 — per-app medians are the reading that survives that best, and per-edit C107 numbers should not be quoted without the caveat `[measured on c107]`.

### Four build costs on the C107

Every cell is a **median over apps** of that app's own median; min/max are across apps, so all four rows are comparable `[measured on c107]`:

| Cost                                               | median | min app | max app | apps |
| -------------------------------------------------- | ------ | ------- | ------- | ---- |
| Quick Build save->live, warm edits                 | 10.1 s | 2.1 s   | 53.3 s  | 18   |
| Incremental standard build (warm daemon)           | 26.6 s | 19.2 s  | 53.6 s  | 21   |
| Cold standard build (full Run)                     | 133 s  | 82 s    | 357 s   | 21   |
| Marginal Quick Build setup (project already built) | 92 s   | 77 s    | 162 s   | 21   |

### Essentially all of the latency is compile

`[measured on c107]`

| Stage                  | Median |
| ---------------------- | ------ |
| compile                | 10.3 s |
| stage (deploy handoff) | 13 ms  |
| apply (live reload)    | 89 ms  |

- Deploy and reload are free at this tier too, as on the A56; further speedup has to come out of compile.
- The compile split is a function of the app's language, not a single blended number `[measured on c107]`:
  - **resource edits** — aapt2 only, ~5-6 s save->live, dominated by aapt2 link;
  - **Java edits** — javac + d8, ~2 s once warm, 11 s on the session's first edit;
  - **Kotlin edits** — dominated by kotlinc: 43.9 s of a 51.1 s `medium-kotlin` edit.

## The 1.9 GB wall is a crawl, not a cliff

Stock behaviour `[measured on itel]`: continuous lmkd "device is not responding" culling of background packages; the build never reaches `standard_build_finished`; the daemon dies at the harness's 180 s init timeout. Force-stopping CoGo recovered available RAM from ~500 MB to ~971 MB, confirming the daemon was the memory sink.

Debloated + screen-off changed the outcome not at all — still `GAP 0/1` — but exposed the mechanism:

| Phase                          | Duration    | What the daemon was doing                                    |
| ------------------------------ | ----------- | ------------------------------------------------------------ |
| Gradle startup + configuration | **8.8 min** | SerialGC thrash — heap pinned 450-604 MB against the 616 MB RAM-scaled Xmx ceiling, 200-293% CPU but mostly GC |
| Task execution                 | ~6 min      | real compile, MemAvailable ~300 MB, SwapFree draining to ~220 MB |
| Outcome                        | —           | force-stopped at 900 s, no `standard_build_finished`         |

Measured daemon sizing `[measured on c107, itel]`:

| Device | RAM    | Xmx   | MaxMetaspace               | GC       |
| ------ | ------ | ----- | -------------------------- | -------- |
| C107   | 3.6 GB | 1304m | 384m (192m pre-fix, OOM'd) | default  |
| itel   | 1.9 GB | 616m  | 384m                       | SerialGC |

- A `hello-java` build that takes **82 s on the C107** did not finish in **15 minutes** on the debloated itel — and more than half of that was Gradle startup and configuration alone.
- So the binding constraint is **the daemon's undersized 616 MB heap**, not a direct lmkd kill `[inferred, from the measured GC and heap behaviour]`; lmkd churn is a symptom.
- Neither debloating nor heap retuning can work here: there is no RAM to enlarge the heap with (~300 MB free mid-build), and shrinking it worsens the GC thrash.

## The Q8 is below the floor before anything starts

At idle, on CoGo's onboarding screen, against a MemTotal of 1.46 GB `[measured on Q8]`:

| Metric               | At idle |
| -------------------- | ------- |
| Available            | 795 MB  |
| Free                 | 43 MB   |
| Already in zram swap | ~558 MB |

- That is worse than the itel's *failing* state before any Gradle work begins. No build was attempted; the idle profile settles it.
