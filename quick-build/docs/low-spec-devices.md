# Below the A56: what Quick Build does on low-spec devices

Status: measured on three low-end devices. The floor is real, it sits between
1.9 GB and 3.6 GB of RAM, and it is **not** Quick Build's floor — it is the
standard Gradle build's.

Provenance tags are mandatory: `[measured on a56]` (Samsung A56, mid-tier),
`[measured on c107]` (C107, 3.6 GB), `[measured on itel]` (itel A667L, 1.9 GB),
`[measured on Q8]` (incar Q8, 1.46 GB), `[inferred]`, `[unmeasured]`.

Primary evidence: `corpus/results/analysis/c107-lowend-report-2026-07-25.md` in
the benchmark repo. This page is the consolidated story; that report is the data.
The push-button protocol for running the farm devices is
[`low-spec-runbook.md`](low-spec-runbook.md) — that answers "how do I run this",
this answers "what happened when we did".

## The floor, in one table

| Device | RAM | Quick Build | Standard Gradle build | Verdict |
|---|---|---|---|---|
| A56 | 8 GB | works | works | reference |
| C107 | 3.6 GB | provisions; 68-70 of 97 edits measured | 30/30 cold, 21/30 marginal | **works** `[measured on c107]` |
| itel A667L | 1.9 GB | never provisions | never finishes | **walled** `[measured on itel]` |
| incar Q8 | 1.46 GB | not reached | not reached | **below floor at idle** `[measured on Q8]` |

Both Quick Build provisioning and a plain `:app:assembleDebug` die on the itel for
the same reason, because both run the same CoGo-sized Gradle daemon. Nothing in
Quick Build's own hot loop is implicated.

## The C107 works, and the absolute win is bigger there

Quote **~2.5x** for the C107, per [`benchmarking.md`](benchmarking.md), which owns
the speedup methodology: that is the per-app median with repeated measurements of
the same edit collapsed first (2.49x over 18 apps). Without that collapse the same
per-app calculation gives 2.69x, which is where the circulated "2.7x" comes from —
a defensible variant, but not the one the set quotes, because collapsing stops an
app with many repeats of one edit from dominating its own median.

The ratio barely moves against the A56's ~2.3x. What changes is the **absolute**
saving, which grows from ~3 s to ~15 s per edit. That is the number that matters
for the product: on the tier CoGo actually targets, Quick Build turns a wait you
leave the phone for into one you sit through.

Three build costs on the C107, same corpus `[measured on c107]`:

| Cost | median | min | max | n |
|---|---|---|---|---|
| Quick Build save->live | 11.3 s | 0.45 s | 236 s | 68/97 |
| Incremental standard build (warm daemon) | 25.4 s | 10.3 s | 53.6 s | 30/30 |
| Cold standard build (full Run) | 131 s | 82 s | 543 s | 30/30 |
| Marginal Quick Build setup (project already built) | 92 s | 77 s | 162 s | 21/30 |

**Essentially all of the Quick Build latency is compile** `[measured on c107]`:
compile 10.3 s median, stage (deploy handoff) 13 ms, apply (live reload) 89 ms.
The deploy and reload machinery is free at this tier too, so any further speedup
has to come out of compile. That matches the A56.

The compile split is a function of the app's language, not a single blended number
`[measured on c107]`: pure resource edits run aapt2 only (~5-6 s save->live,
dominated by aapt2 link); Java edits are javac + d8 (~2 s once warm, 11 s on the
session's first edit); Kotlin edits are dominated by kotlinc (43.9 s of a 51.1 s
`medium-kotlin` edit).

## The Metaspace fix that unblocked the C107

Before commit `6d198f576`, `LowMemoryStrategy` and `BalancedStrategy` both capped
the Gradle daemon at `MaxMetaspaceSize=192m`. AGP plus Kotlin class metadata alone
exceeds that, so `:app:assembleDebug` died in `OutOfMemoryError: Metaspace` before
finishing — reproduced on the C107, which then builds green at **384m**
`[measured on c107]`. 384 matches `HighPerformanceStrategy`, so all three tiers
now agree on the Metaspace cap while the low tiers keep their smaller heaps.

The cap is **necessary but not sufficient** below ~3.6 GB. It does nothing for the
itel. Measured daemon sizing `[measured on c107, itel]`:

| Device | RAM | Xmx | MaxMetaspace | GC |
|---|---|---|---|---|
| C107 | 3.6 GB | 1304m | 384m (192m pre-fix, OOM'd) | default |
| itel | 1.9 GB | 616m | 384m | SerialGC |

## Per-tier Gradle daemon idle timeouts

Added in `916aa066b`. An idle Gradle daemon holds its full heap, which on a
low-RAM device is memory better handed back to the Quick Build daemon and the IDE.
`GradleBuildTuner` now emits `-Dorg.gradle.daemon.idletimeout=<ms>` as a
command-line system property (which overrides `gradle.properties` and is fixed at
daemon startup):

| Tier | Trigger | Idle timeout |
|---|---|---|
| `low_memory` | <=3 GB or `isLowRamDevice` | 15 min |
| `balanced` | 3-6 GB | 30 min |
| `high_performance` | 6 GB+ | 2 h |
| `thermal_safe` | — | inherits the previous config unchanged |

The 2 h high-performance value is deliberately distinct from Gradle's 3 h default
so the daemon log proves the property flowed end to end. Unit tests cover arg
emission and a tier-inversion guard — less RAM must never keep an idle daemon
*longer*. **Not yet device-verified**: no run has confirmed the reclaim actually
helps a low-RAM session `[unmeasured]`.

## The 1.9 GB wall is a crawl, not a cliff

The short version — "the daemon dies under continuous lmkd pressure" — is how this
was first framed, and a debloat experiment refined it into something more useful
`[measured on itel]`.

Stock, the itel enters continuous lmkd "device is not responding" culling:
background packages are killed one after another, the build never reaches
`standard_build_finished`, and the daemon dies at the harness's 180 s init
timeout. Force-stopping CoGo recovered available RAM from ~500 MB to ~971 MB,
confirming the daemon was the memory sink.

Two waves of package-disabling (16 packages by wave 2, Bryan-approved, snapshot
kept, fully restored afterward) plus a screen-off condition and a generous 900 s
window changed the outcome not at all — still `GAP 0/1` — but exposed the
mechanism `[measured on itel, debloated + screen-off; do not compare naively
against stock runs]`:

| Phase | Duration | What the daemon was doing |
|---|---|---|
| Gradle startup + configuration | **8.8 min** | SerialGC thrash — heap pinned 450-604 MB against the 616 MB RAM-scaled Xmx ceiling, 200-293% CPU but mostly GC |
| Task execution | ~6 min | real compile, MemAvailable ~300 MB, SwapFree draining to ~220 MB |
| Outcome | — | force-stopped at 900 s, no `standard_build_finished` |

A `hello-java` build that takes **82 s on the C107** did not finish in **15
minutes** on the debloated itel, and more than half of that was Gradle startup and
configuration alone.

So the binding constraint is **the daemon's undersized 616 MB heap**, not a direct
lmkd kill of the daemon `[inferred, from the measured GC and heap behaviour]`.
lmkd churn of background apps is real and continuous, but it is a symptom. This
refines the original framing usefully: debloating and heap retuning both fail, and
they fail for a reason that says neither will ever work — there is no RAM to
enlarge the heap with (~300 MB free mid-build), and shrinking it makes the GC
thrash worse.

## The Q8 is below the floor before anything starts

At idle, on CoGo's onboarding screen, the Q8 has **795 MB available / 43 MB free /
~558 MB already in zram swap** against a MemTotal of 1.46 GB `[measured on Q8]` —
worse than the itel's *failing* state before any Gradle work begins. No build was
attempted; the idle profile settles it.

Two practical notes for anyone picking this device up: it is a fixed-landscape
in-car head unit that renders app content rotated 90 degrees inside a 600x1024
portrait framebuffer, and its onboarding carousel did not respond to programmatic
taps or swipes, so completing setup needs a human touching the screen.

## Two open items

**1. The C107's unattributed compile time, which compounds.** On the A56 the
measured spans account for 91% of a warm edit and the ~9% residual is *fixed*. The
C107's `medium-kotlin` row leaves **52% of `compileMs` unattributed**, and it got
*worse* across a session — 188 s then 236 s — where the A56 got better, 53.6 s then
16.5 s `[measured on c107]` / `[measured on a56]`. A fixed per-operation tax cannot
compound; something there accumulates. The leading hypothesis is the daemon's JVM
heap, argued in full in [`perf-roadmap.md`](perf-roadmap.md); task #105.

**This needs reconciling before anyone acts on it.** The 07-25 C107 report offers
a competing partial explanation for the same residual: builds queued behind an
in-flight build start ~1 ms after the previous deploy, so their
`trigger->compileDone` **includes queue wait**, and it concludes the multi-second
residual on `medium-kotlin`'s later builds is "mostly queue wait from this
pipelining, not hidden compile overhead" `[measured on c107]`. Whether that
overlaps the 52% figure depends on whether `compileMs` is measured as
`trigger->compileDone` (in which case the two claims compete directly) or excludes
queue wait (in which case they are separate). Settle that first — a harness
artefact and a daemon-heap problem call for very different work.

**2. The storage finding is expected to reproduce here, and has not been run.**
The Quick Build daemon's scratch tree sits on FUSE-backed emulated storage, where
per-file I/O costs ~50x what it costs on app-private storage; moving it cuts a warm
A56 edit by 45% `[measured on a56]`. See
[`../../docs/on-device-storage-performance.md`](../../docs/on-device-storage-performance.md).
The C107 is 4-13x slower overall and was **not** re-measured. The finding should be
*expected* to reproduce and be worse there — the extra CPU that FUSE round trips
consume is scarcest exactly where hardware is weakest — but that is `[inferred]`
until run, and it does **not** explain open item 1, because a fixed per-file toll
cannot produce a session that degrades.

## What this means for the product

- **Quick Build is worth shipping on the C107 tier.** ~2.5x, ~15 s saved per edit,
  on the hardware class the mission targets `[measured on c107]`.
- **Nothing Quick Build can do reaches 1.9 GB today**, because the standard setup
  build cannot run there. Raising Quick Build's reachable device range is a
  standard-build problem, not a Quick Build one.
- **There is one lever that could change that** `[inferred]`: the wall is entirely
  in the Gradle setup layer, where the large daemon JVM lives. The Quick Build hot
  loop is a much smaller runtime. If provisioning could be served without a full
  on-device Gradle build — a prebaked baseline shipped with the project, for
  instance — the hot loop *might* still be viable at 1.9 GB even though the setup
  build is not. Worth a spike before anyone concludes the tier is permanently out
  of reach.
- **Close the two open items before the low-end numbers are quoted as final.** One
  of them may be a harness artefact.
