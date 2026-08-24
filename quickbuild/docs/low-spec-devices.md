# Low-spec devices: on-device Gradle is the wall, not Quick Build

**CoGo itself runs on 2 GB devices, and people use it there** - Hal reports community members
doing so on 32-bit 2 GB hardware (ADFA-4929). **What we have not got working at that tier is the
full on-device *Gradle* build every Quick Build session must start with.** We never watched one
fail: all three itel attempts ended at a timeout we chose, so "too slow for us to wait out" is the
honest claim, and whether it would finish given longer is unmeasured. Nothing measured implicates
Quick Build's own runtime - the live reload loop has never failed on its own at any tier tested,
and has never been tested at 2 GB because provisioning never got far enough to start it.

**Decided (Bryan, 2026-08-05): the 4 GB tier is the target; 1.9 GB moves to a later ticket.** Two
devices now measure at 4 GB nominal and both run a full session, so that tier is where hardening
effort pays off. The Gradle-free-provisioning spike below is the 1.9 GB answer and is not being
scoped now - it stays on this page as the evidence for whoever picks that ticket up.

Primary evidence, with the full runbook and cost tables, lives in the
`CodeOnTheGo-build-benchmark` repo.

## What was actually measured

| Device | RAM | On-device Gradle build | Quick Build |
| --- | --- | --- | --- |
| A56 | 8 GB | works | works - reference device `[measured on a56]` |
| C107 | 3.6 GB | works | works: Ready on 21/30 corpus apps `[measured on c107, earlier pass]` |
| Galaxy A06 | 3.55 GB | works | works: 79 measured edits across 24 corpus apps `[measured on a06]` |
| itel A667L | 1.9 GB | too slow to use - see below | never reached `[measured on itel]` |
| incar Q8 | 1.46 GB | not attempted | not reached `[measured on Q8]` |

- **The C107's 9 misses are a corpus artifact, not a device limit** - 5 died on
  offline-unresolvable KMP coordinates (fixed host-side since) and the other 4 fail on the A56
  too. "21 of 30" describes our corpus, not the C107.
- **The C107 gains more from Quick Build than the A56 does**, not less: across the 19 edits
  measured on both, its speedup is higher on all 19 (median 1.77x), saving a median 17.5 s per
  edit against 3.1 s on the A56 `[measured on c107, earlier pass]`. The C107 has not been in a pass
  since, so this row is historical - no current-pass C107 data exists.
- **The A06 shows the same pattern, and it holds in the current pass.** Quick Build beats the
  standard incremental build by a wider margin on the A06 than on the A56 `[measured on a06, a56]`.
- **At the 4 GB tier, CPU decides the experience, not RAM** - but the two 4 GB devices have never
  been measured against each other on comparable terms. Each was compared to the A56 instead, and
  the two comparisons sit in different eras. Both rows are from earlier passes, superseded as
  headline figures by the 2026-08-11 pass and kept because nothing in that pass replaces them - it
  carries no C107, and it matches no app across devices:

  | Comparison | Build | Apps matched | Result |
  | --- | --- | --- | --- |
  | C107 vs A56 | earlier pass, scratch on FUSE | 21 of 21 | C107 **3.5x** slower `[measured on c107, a56; earlier pass]` |
  | A06 vs A56 | earlier passes, scratch off FUSE | 7 of 7 | A06 **2.5x** slower `[measured on a06, a56; earlier passes]` |

  For scale in the current pass, unmatched: both the A06's median save to live and its median
  standard incremental build are several times the A56's `[measured on a06, a56]`. Different app
  sets, so this is not a paired cross-device ratio.

  Chaining those through the A56 puts the C107 at ~1.4x the A06, but the chain crosses the
  scratch-off-FUSE change, so treat the A06-vs-C107 gap as `[inferred]`, not measured. What is solid
  is the ordering and that both 4 GB devices are usable. The A06's eight Cortex-A55 cores with no
  big core are the likely reason it still trails the A56 `[inferred]`. So "4 GB device" is not a
  performance class on its own - do not treat one 4 GB measurement as covering the tier.
- **Where the floor actually sits is still unknown.** Both 4 GB-tier devices we own sit within
  50 MB of each other, so they do not narrow the gap: "~3.6 GB works, 1.9 GB does not" remains the
  whole of what we know `[measured on a06, c107, itel]`.

## Why the 1.9 GB device fails

Not a hard RAM wall, and not a direct lmkd kill of the daemon - it is CoGo's own heap sizing
colliding with the device. CoGo scales the Gradle daemon JVM to device RAM; at 1.9 GB the resulting
heap is small enough that SerialGC thrashes.

| Gradle daemon on the itel (1.9 GB) | itel | C107 (3.6 GB) |
| --- | --- | --- |
| Heap ceiling CoGo picks | `-Xmx616m`, SerialGC | `-Xmx1304m` |
| Heap in use mid-build | 450-604 MB | - |
| Daemon CPU, almost all GC | 200-293% | - |
| Startup + configuration, trivial project | ~8.8 min `[debloated + screen-off]` | - |
| `hello-java` build | unfinished when we stopped it at 15 min | 82 s |

All rows `[measured on itel, c107]`.

- **Retuning the heap cannot fix it**: there is no RAM to grow into, ~300 MB free mid-build
  `[inferred]`.
- The Q8 (1.46 GB) never got a build attempt - at idle it already showed 795 MB available, 43 MB
  free and ~558 MB in zram, worse than the itel's *failing* mid-build state `[measured on Q8]`.

### What we actually observed, per attempt

We never watched a build fail on its own. Every attempt ended at a timeout **we** chose, so the
claim is "too slow to be usable", not "never terminates" - those are different results and only
the first is measured `[measured on itel]`.

| Run | Condition | Our cap | Build reported started | Outcome |
| --- | --- | --- | --- | --- |
| `20260725T224033Z` | stock | 300 s | no | cut off at the cap |
| `20260726T055750Z` | debloat wave 1, 11 pkgs | 300 s | no | cut off at the cap |
| `20260726T060809Z` | debloat wave 2, 16 pkgs, screen off | 900 s | yes, after ~8.8 min | ~6 min of task execution, still running at the cap |

- Only the 900 s run says anything about the shape of the failure: startup plus configuration
  alone took ~8.8 min, task execution had been running ~6 min more, and nothing in the log
  suggested it was stuck rather than crawling.
- **Debloating changed the failure mode, not the outcome** - enough headroom to reach task
  execution instead of being cut off before the build started, which is what exposed GC thrash as
  the mechanism.
- **Where an uncapped build would land is unmeasured.** Nobody has run one to completion or to a
  self-reported failure on this device.

## The 1.9 GB option, for the later ticket: spike Gradle-free provisioning

The lever follows from the mechanism: what dies is the big Gradle daemon JVM, and the live reload
loop is a far smaller runtime. If a session could be provisioned *without* an on-device Gradle
build - a baseline prebaked and shipped with the project - the loop might run at 1.9 GB even though
the setup build cannot `[inferred]`.

- The test is cheap and decisive, which is the main argument for running it: prebake a `hello-java`
  baseline, push it to the itel, run one warm edit. It completes or it doesn't.
- Payoff: reopens a device tier the mission targets and CoGo already ships to.
- Cost `[assumed]`: a few days to a first answer; sizing it properly is the spike's own first
  deliverable.

## Still unmeasured

- **The warm live reload loop at 1.9 GB** - the headline unknown, and what the spike would settle.
- **The Gradle daemon heap CoGo picks on the A06**, and whether `GRADLE_METASPACE_MB` (192 to 384)
  is right for a 4 GB device with eight small cores - an open question for Akash `[unmeasured]`.
- Share of the target audience near 2 GB `[unmeasured - needs market-share data]`.
- Anything between 1.9 GB and 3.6 GB; we own no such device.
- `2048` and `ruler` reach Ready but every edit GAPs on a deploy failure on both tiers, while the
  standard build of the same edits passes. Closes when reproduced by hand as a real bug and filed,
  or traced to the harness and excluded.
- `todo-list` has one scripted edit, and warm comparisons only count edits after an app's first,
  so it never yields a warm measurement. Closes when a second edit is added.
