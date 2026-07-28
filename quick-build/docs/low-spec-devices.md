# Decision: Quick Build works at 3.6 GB and stops at 1.9 GB — do we try to go lower?

**Status**: measured on three low-end devices. The floor is real and no decision has been taken about where we draw the supported minimum spec.

**Provenance**: `[measured on a56]` (Samsung A56, mid-tier), `[measured on c107]` (C107, 3.6 GB), `[measured on itel]` (itel A667L, 1.9 GB), `[measured on Q8]` (incar Q8, 1.46 GB), `[inferred]`, `[unmeasured]`.

**Primary evidence**: `corpus/results/analysis/c107-lowend-report-2026-07-25.md` in the benchmark repo. This page is the consolidated story; that report is the data. For how to run the farm devices, see [`low-spec-runbook.md`](low-spec-runbook.md) — that answers "how do I run this", this answers "what happened when we did".

## Summary

- Quick Build works on the C107 (3.6 GB): **~2.9x** faster than the standard build, saving **+15.1 s** on the median app's warm edit — a bigger absolute saving than the A56 gets.
- The floor sits between 1.9 GB and 3.6 GB:
  - C107, 3.6 GB — works
  - itel A667L, 1.9 GB — walled: never provisions, never finishes a build
  - incar Q8, 1.46 GB — below the floor at idle, before anything starts
- **The wall is not Quick Build's.** What fails at 1.9 GB is the standard Gradle build that provisions it; Quick Build adds no failures of its own on any tier we measured. No amount of Quick Build work moves the floor.
- The one lever that could `[inferred]`: serve provisioning without a full on-device Gradle build. The Quick Build hot loop is a much smaller runtime than the Gradle daemon that dies.
- **Decision: do we accept 3.6 GB as the supported minimum, or spike Gradle-free provisioning to try to reach 1.9 GB?**
  - Would put a device tier the mission targets back in reach — today nothing Quick Build can do gets there.
  - Two open items should close first; one of them may be a harness artefact.

## The floor, in one table

| Device | RAM | Quick Build | Standard Gradle build | Verdict |
|---|---|---|---|---|
| A56 | 8 GB | works | works | reference |
| C107 | 3.6 GB | reaches Ready on 21/30 apps; 68-70 of 97 edits measured | builds 21/30 apps, cold and incremental | **works** `[measured on c107]` |
| itel A667L | 1.9 GB | never provisions | never finishes | **walled** `[measured on itel]` |
| incar Q8 | 1.46 GB | not reached | not reached | **below floor at idle** `[measured on Q8]` |

- The two C107 columns are the **same 21 apps** `[measured on c107]`:
  - every app that reaches Quick Build Ready also builds by the standard route;
  - the 9 that do not reach Ready are exactly the 9 CoGo cannot build at all there;
  - so Quick Build adds no failures of its own on this tier.
- An earlier revision of this table read "30/30 cold, 21/30 marginal". The 30/30 was wrong, and the 21/30 was a Quick Build setup count printed under the standard-build column.
- On the itel, both Quick Build provisioning and a plain `:app:assembleDebug` die for the same reason: both run the same CoGo-sized Gradle daemon. Nothing in Quick Build's own hot loop is implicated.

## The C107 works, and the absolute win is bigger there

### Quote ~2.9x, and carry its caveat

- **~2.9x**, per [`benchmarking.md`](benchmarking.md), which owns the speedup methodology: the per-app median over 18 apps, with repeated measurements of the same edit collapsed first.
- Figures that do **not** reproduce from the CSVs:
  - the circulated **2.7x** — under no reading; the nearest is the same per-app calculation without collapsing repeats, **2.75x**;
  - **2.49x**, quoted by an earlier revision of this page.
- **34% of the C107's measured edits are attribution-suspect**, against 7% on the A56.
  - Per-app medians are the reading that survives that best.
  - Per-edit C107 numbers should not be quoted without the caveat — see benchmarking.md, "How much of this to believe".

### The ratio moves against the A56; the absolute saving is what matters

The C107's ~2.9x is above the A56's ~2.3x, but the number the product turns on is time saved `[measured on a56, c107]`:

| Device | Saving on the median app's warm edit |
|---|---|
| A56 | +2.7 s |
| C107 | **+15.1 s** |

On the tier CoGo actually targets, Quick Build turns a wait you leave the phone for into one you sit through.

### Four build costs on the C107

Same corpus. Every cell is a **median over apps** of that app's own median, and min/max are across apps, so all four rows are comparable to each other `[measured on c107]`:

| Cost | median | min app | max app | apps |
|---|---|---|---|---|
| Quick Build save->live, warm edits | 10.1 s | 2.1 s | 53.3 s | 18 |
| Incremental standard build (warm daemon) | 26.6 s | 19.2 s | 53.6 s | 21 |
| Cold standard build (full Run) | 133 s | 82 s | 357 s | 21 |
| Marginal Quick Build setup (project already built) | 92 s | 77 s | 162 s | 21 |

Those four medians do **not** divide into the 2.9x headline, and should not be made to: the headline is the median of each app's *own* ratio, which is the right statistic when the standard-build baseline varies 3x across apps.

### Three corrections to the earlier version of that table

All from re-deriving it:

- **The standard-build rows said `30/30`.** Only 21 of 30 apps complete a standard build on the C107; the 9 that fail were contributing their *failed* builds' durations. That is where the old `10.3 s` incremental minimum, the `543 s` cold maximum and the `131 s` cold median came from.
- **The old Quick Build row (`11.3 s`, `68/97`) was one sweep**, not the pooled corpus.
- **It also pooled cold first edits with warm ones.** Pooled across everything the C107 measured, the row-level median is 10.3 s and the per-app median 11.8 s — both above the 10.1 s warm figure the speedup is built on.

### Essentially all of the latency is compile

`[measured on c107]`

| Stage | Median |
|---|---|
| compile | 10.3 s |
| stage (deploy handoff) | 13 ms |
| apply (live reload) | 89 ms |

- The deploy and reload machinery is free at this tier too, so any further speedup has to come out of compile. That matches the A56.
- The compile split is a function of the app's language, not a single blended number `[measured on c107]`:
  - **resource edits** — aapt2 only, ~5-6 s save->live, dominated by aapt2 link;
  - **Java edits** — javac + d8, ~2 s once warm, 11 s on the session's first edit;
  - **Kotlin edits** — dominated by kotlinc: 43.9 s of a 51.1 s `medium-kotlin` edit.

## The two fixes that got the C107 there

### The Metaspace cap was killing the standard build

- Before commit `6d198f576`, `LowMemoryStrategy` and `BalancedStrategy` both capped the Gradle daemon at `MaxMetaspaceSize=192m`.
- AGP plus Kotlin class metadata alone exceeds that, so `:app:assembleDebug` died in `OutOfMemoryError: Metaspace` before finishing — reproduced on the C107, which then builds green at **384m** `[measured on c107]`.
- 384 matches `HighPerformanceStrategy`, so all three tiers now agree on the Metaspace cap while the low tiers keep their smaller heaps.
- The cap is **necessary but not sufficient** below ~3.6 GB. It does nothing for the itel.

Measured daemon sizing `[measured on c107, itel]`:

| Device | RAM | Xmx | MaxMetaspace | GC |
|---|---|---|---|---|
| C107 | 3.6 GB | 1304m | 384m (192m pre-fix, OOM'd) | default |
| itel | 1.9 GB | 616m | 384m | SerialGC |

### Per-tier Gradle daemon idle timeouts

Added in `916aa066b`. An idle Gradle daemon holds its full heap, which on a low-RAM device is memory better handed back to the Quick Build daemon and the IDE. `GradleBuildTuner` now emits `-Dorg.gradle.daemon.idletimeout=<ms>` as a command-line system property (which overrides `gradle.properties` and is fixed at daemon startup):

| Tier | Trigger | Idle timeout |
|---|---|---|
| `low_memory` | <=3 GB or `isLowRamDevice` | 15 min |
| `balanced` | 3-6 GB | 30 min |
| `high_performance` | 6 GB+ | 2 h |
| `thermal_safe` | — | inherits the previous config unchanged |

- The 2 h high-performance value is deliberately distinct from Gradle's 3 h default so the daemon log proves the property flowed end to end.
- Unit tests cover arg emission and a tier-inversion guard — less RAM must never keep an idle daemon *longer*.
- **Not yet device-verified**: no run has confirmed the reclaim actually helps a low-RAM session `[unmeasured]`.

## The 1.9 GB wall is a crawl, not a cliff

The short version — "the daemon dies under continuous lmkd pressure" — is how this was first framed, and a debloat experiment refined it into something more useful `[measured on itel]`.

Stock behaviour:

- The itel enters continuous lmkd "device is not responding" culling — background packages killed one after another.
- The build never reaches `standard_build_finished`; the daemon dies at the harness's 180 s init timeout.
- Force-stopping CoGo recovered available RAM from ~500 MB to ~971 MB, confirming the daemon was the memory sink.

Debloated: two waves of package-disabling (16 packages by wave 2, Bryan-approved, snapshot kept, fully restored afterward), plus a screen-off condition and a generous 900 s window. This changed the outcome not at all — still `GAP 0/1` — but exposed the mechanism `[measured on itel, debloated + screen-off; do not compare naively against stock runs]`:

| Phase | Duration | What the daemon was doing |
|---|---|---|
| Gradle startup + configuration | **8.8 min** | SerialGC thrash — heap pinned 450-604 MB against the 616 MB RAM-scaled Xmx ceiling, 200-293% CPU but mostly GC |
| Task execution | ~6 min | real compile, MemAvailable ~300 MB, SwapFree draining to ~220 MB |
| Outcome | — | force-stopped at 900 s, no `standard_build_finished` |

- A `hello-java` build that takes **82 s on the C107** did not finish in **15 minutes** on the debloated itel — and more than half of that was Gradle startup and configuration alone.
- So the binding constraint is **the daemon's undersized 616 MB heap**, not a direct lmkd kill of the daemon `[inferred, from the measured GC and heap behaviour]`. lmkd churn of background apps is real and continuous, but it is a symptom.
- This refines the original framing usefully: debloating and heap retuning both fail, and they fail for a reason that says **neither will ever work** —
  - there is no RAM to enlarge the heap with (~300 MB free mid-build), and
  - shrinking it makes the GC thrash worse.

## The Q8 is below the floor before anything starts

At idle, on CoGo's onboarding screen, against a MemTotal of 1.46 GB `[measured on Q8]`:

| Metric | At idle |
|---|---|
| Available | 795 MB |
| Free | 43 MB |
| Already in zram swap | ~558 MB |

That is worse than the itel's *failing* state before any Gradle work begins. No build was attempted; the idle profile settles it.

Two practical notes for anyone picking this device up:

- It is a fixed-landscape in-car head unit that renders app content rotated 90 degrees inside a 600x1024 portrait framebuffer.
- Its onboarding carousel did not respond to programmatic taps or swipes, so completing setup needs a human touching the screen.

## Options, with their costs

1. **Ship Quick Build on the C107 tier and treat 3.6 GB as the supported minimum.**
   - ~2.9x over 18 apps and +15.1 s saved on the median app's warm edit, on the hardware class the mission targets `[measured on c107]`.
   - Cost: nothing below 3.6 GB is served. Nothing Quick Build can do reaches 1.9 GB today, because the standard setup build cannot run there — raising Quick Build's reachable device range is a standard-build problem, not a Quick Build one.
2. **Spike provisioning without a full on-device Gradle build** — a prebaked baseline shipped with the project, for instance `[inferred]`.
   - The wall is entirely in the Gradle setup layer, where the large daemon JVM lives; the Quick Build hot loop is a much smaller runtime, so it *might* still be viable at 1.9 GB even though the setup build is not.
   - Cost: the spike itself is unscoped, and the hypothesis is `[inferred]` — no run has shown the hot loop surviving at 1.9 GB.
   - Worth doing before anyone concludes the tier is permanently out of reach.
3. **Ruled out by measurement: debloating or retuning the heap on the itel.** Both were tried and both failed, for the reason above — no RAM to grow the heap with, and shrinking it worsens the GC thrash.

## Two open items to close before the low-end numbers are quoted as final

### 1. The C107's unattributed compile time, which compounds

On the A56 the measured spans account for 91-93% of a warm edit and the 7-9% residual is *fixed*. On the C107 two separate observations point the same way, and they are **two different apps** — an earlier revision of this page welded them into one sentence:

- `medium-kotlin`'s later builds leave a large share of `compileMs` unattributed `[measured on c107]`.
  - Only 13 rows in the whole corpus carry sub-step timings, all from one C107 sweep.
  - Across `medium-kotlin`'s four, the unaccounted share of `compileMs` is 1%, 3%, 59% and 36% — 17% pooled over the app.
  - The circulated "52%" does not reproduce from those rows under any grouping tried; quote the per-row figures or the 17% pooled.
- `sora-editor-full` got *slower* across a C107 session — 188.8 s then 236.3 s — where the same app on the A56 got faster, 53.6 s then 16.5 s `[measured on c107]` / `[measured on a56]`.

A fixed per-operation tax cannot compound; something there accumulates. The leading hypothesis is the daemon's JVM heap, argued in full in [`perf-roadmap.md`](perf-roadmap.md); task #105.

**This needs reconciling before anyone acts on it.** The 07-25 C107 report offers a competing partial explanation for the same residual `[measured on c107]`:

- Builds queued behind an in-flight build start ~1 ms after the previous deploy, so their `trigger->compileDone` **includes queue wait**.
- The report concludes the multi-second residual on `medium-kotlin`'s later builds is "mostly queue wait from this pipelining, not hidden compile overhead".
- Whether that overlaps the 52% figure depends on how `compileMs` is measured:
  - as `trigger->compileDone` — the two claims compete directly;
  - excluding queue wait — they are separate.
- Settle that first: a harness artefact and a daemon-heap problem call for very different work.

### 2. The storage finding is expected to reproduce here, and has not been run

- The Quick Build daemon's scratch tree sits on FUSE-backed emulated storage, where per-file I/O costs ~50x what it costs on app-private storage; moving it cuts a warm A56 edit by 45% `[measured on a56]`. See [`../../docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md).
- The C107 is 4-13x slower overall and was **not** re-measured.
- The finding should be *expected* to reproduce and be worse there — the extra CPU that FUSE round trips consume is scarcest exactly where hardware is weakest — but that is `[inferred]` until run.
- It does **not** explain open item 1: a fixed per-file toll cannot produce a session that degrades.
