# Low-spec devices: on-device Gradle is the wall, not Quick Build

**CoGo itself runs on 2 GB devices, and people use it there.** What does not run on one is a
full on-device *Gradle* build - and today every Quick Build session has to start with one. So
Quick Build cannot reach that tier yet, but nothing we measured says its own runtime is the
reason: the live reload loop has never failed on its own at any tier tested, and it has never
been tested at 2 GB, because provisioning never gets far enough to start it.

That is the open question this page exists to frame, and the reason the Gradle-free provisioning
spike below is worth costing.

Primary evidence, with the full runbook and cost tables:
`corpus/results/analysis/c107-lowend-report-2026-07-25.md` in the `CodeOnTheGo-build-benchmark`
repo.

## What was actually measured

| Device | RAM | On-device Gradle build | Quick Build |
| --- | --- | --- | --- |
| A56 | 8 GB | works | works - reference device `[measured on a56]` |
| C107 | 3.6 GB | works | works: Ready on 21/30 corpus apps `[measured on c107]` |
| itel A667L | 1.9 GB | never finishes | never reached `[measured on itel]` |
| incar Q8 | 1.46 GB | not attempted | not reached `[measured on Q8]` |

- **The C107's 9 misses are a corpus artifact, not a device limit** - 5 died on
  offline-unresolvable KMP coordinates (fixed host-side since) and the other 4 fail on the A56
  too. "21 of 30" describes our corpus, not the C107.
- **The C107 gains more from Quick Build than the A56 does**, not less: across the 19 edits
  measured on both, its speedup is higher on all 19 (median 1.77x), saving a median 17.5 s per
  edit against 3.1 s on the A56 `[measured on c107]`.
- **Where the floor actually sits is unknown.** We have no device between 1.9 GB and 3.6 GB, so
  "3.6 GB works, 1.9 GB does not" is the whole of what we know `[measured on c107, itel]`.

## Why the 1.9 GB device fails

Not a hard RAM wall, and not a direct lmkd kill of the daemon - it is CoGo's own heap sizing
colliding with the device.

- CoGo scales the Gradle daemon JVM to device RAM, which yields `-Xmx616m` with SerialGC at
  1.9 GB (against 1304m on the C107) `[measured on itel, c107]`.
- At that size, SerialGC thrashes: the heap pins at 450-604 MB against the 616 MB ceiling, the
  daemon burns 200-293% CPU almost entirely on GC, and **Gradle startup plus configuration alone
  takes ~8.8 minutes** for a trivial project `[measured on itel, debloated + screen-off]`.
- A `hello-java` build that takes 82 s on the C107 had still not finished after 15 minutes
  `[measured on itel]`. It is a crawl slow enough to be unusable, not an instant death.
- **Debloating changed the failure mode, not the outcome** - enough headroom to reach task
  execution instead of dying at harness init, which is what exposed GC thrash as the mechanism.
- **Retuning the heap cannot fix it**: there is no RAM to grow into, ~300 MB free mid-build
  `[inferred]`.
- The Q8 (1.46 GB) never got a build attempt - at idle it already showed 795 MB available, 43 MB
  free and ~558 MB in zram, worse than the itel's *failing* mid-build state `[measured on Q8]`.

## Decision: accept the current floor, or spike Gradle-free provisioning?

The lever follows from the mechanism: what dies is the big Gradle daemon JVM, and the live reload
loop is a far smaller runtime. If a session could be provisioned *without* an on-device Gradle
build - a baseline prebaked and shipped with the project - the loop might run at 1.9 GB even though
the setup build cannot `[inferred]`.

- The falsifier is cheap and decisive, which is the main argument for running it: prebake a
  `hello-java` baseline, push it to the itel, run one warm edit. It completes or it doesn't.
- Payoff: reopens a device tier the mission targets and CoGo already ships to.
- Cost `[assumed]`: a few days to a first answer; sizing it properly is the spike's own first
  deliverable.

## Still unmeasured

- **The warm live reload loop at 1.9 GB** - the headline unknown, and what the spike would settle.
- Share of the target audience near 2 GB `[unmeasured - needs market-share data]`.
- Anything between 1.9 GB and 3.6 GB; we own no such device.
- `2048` and `ruler` reach Ready but every edit GAPs on a deploy failure on both tiers, while the
  standard build of the same edits passes. Closes when reproduced by hand as a real bug and filed,
  or traced to the harness and excluded.
- `todo-list` has one scripted edit, and warm comparisons only count edits after an app's first,
  so it never yields a warm measurement. Closes when a second edit is added.
