# On-device storage performance: emulated storage is ~50x slower per file

Status: measured finding, no decision taken. One fix is scoped (ADFA-4128 task #101); a larger question about project build directories is open and deliberately not decided here.

Provenance: `[measured on a56]` = Samsung A56, 2026-07-28. Everything else is either code reading or explicitly tagged `[inferred]` / `[unmeasured]`.

## The two filesystems

A CoGo device has two storage worlds, and the difference is not a tuning parameter — it is a different I/O architecture. On the A56 `[measured on a56]`:

```
/data              f2fs   (real in-kernel filesystem)
/storage/emulated  fuse   (/dev/fuse, userspace daemon)
```

On `/data`, `open()` enters the kernel, hits the f2fs driver, returns. On `/storage/emulated`, the kernel cannot answer: it packages the request, wakes a **userspace process** (MediaProvider / ExternalStorageProvider), waits for the reply, copies it back. Every `open`, `create`, `write`, `close`, `stat` and `mkdir` is a round trip out to userspace, with context switches and buffer copies at each hop. Android layers more work on that path — per-app permission attribution, case-insensitivity emulation, media-scanner bookkeeping for MTP visibility.

The overhead is roughly **fixed per operation**, so the workload shape decides everything:

- **Few large files:** the cost amortizes over bulk transfer. Barely visible.
- **Many small files:** you pay it on every one. This is what build tools do.

Measured, same 464 files / 1.46 MB / 40 dirs, `cp -r`, best of 3 `[measured on a56]`:

| target                       | time        |
| ---------------------------- | ----------- |
| `/data/local/tmp` (f2fs)     | **192 ms**  |
| `/storage/emulated/0` (FUSE) | **9985 ms** |

**52x.** Not a constant factor on throughput — a per-file toll, on a workload that is nothing but small files.

Historical note: Android used an in-kernel shim (`sdcardfs`) for this path, which was far cheaper. Android 11+ removed it in favour of the userspace daemon required by scoped storage. So this cost grew over time, and it is worst on low-end hardware where the extra CPU is scarcest — exactly our target devices.

## Where CoGo puts what

| what                                                         | location                                    | filesystem | speed |
| ------------------------------------------------------------ | ------------------------------------------- | ---------- | ----- |
| `GRADLE_USER_HOME` (module cache, transforms, daemon registry) | `<filesDir>/home/.gradle`                   | f2fs       | fast  |
| Kotlin daemon state                                          | `<filesDir>/home/.kotlin`                   | f2fs       | fast  |
| Bundled toolchain / Termux prefix                            | `<filesDir>/usr`                            | f2fs       | fast  |
| Plugin unpack targets                                        | host `filesDir`                             | f2fs       | fast  |
| **User project sources**                                     | `/storage/emulated/0/CodeOnTheGoProjects/…` | FUSE       | slow  |
| **Project build output**                                     | same, under the project                     | FUSE       | slow  |
| **Quick Build daemon scratch tree**                          | same, under the project                     | FUSE       | slow  |

The pattern is already mostly right: everything CoGo owns internally lives on f2fs. The exceptions are the things that live *inside the user's project folder* — which is on emulated storage because that is what makes a project visible to the user and reachable over MTP.

## How big, and how widespread

**Measured (Quick Build only).** The QB daemon's scratch tree (`classes/`, `ic/`, `cp-snap/`, `opened-classes/`, `dex/`) sits under the project root. QB rewrites that whole tree every save — the strip pass deletes and rebuilds the mirror, the deploy policy re-reads the changed set — so a one-line edit costs roughly 800 FUSE round trips. Moving only that tree to app-private storage, changing nothing else `[measured on a56]`:

| workload                                   | before      | after                  |
| ------------------------------------------ | ----------- | ---------------------- |
| `sora-editor-full` warm edit (292 sources) | 14.7 s      | **8.1 s (-45%)**       |
| `medium-kotlin` warm edit (28 sources)     | 2.5 s       | **1.4 s (-45%)**       |
| strip pass                                 | 4.7-5.5 s   | 0.17-0.33 s (~20x)     |
| deploy-policy class-header pass            | 0.6-1.2 s   | 0.06-0.11 s (~10x)     |
| output-tree walks                          | 0.24-0.27 s | 0.03-0.05 s (~6x)      |
| kotlinc / d8 (compute-bound controls)      | —           | unchanged within noise |

The controls are what make this a filesystem finding rather than a general speedup: only the per-file I/O steps moved. Note the small app benefits by the same **45%** — this is not a large-project problem. Two apps, one edit each, n=1 before and n=2 after, A56 only: a strong signal with an independent mechanism measurement behind it, but not a distribution, and not grounds for saying "every app size". Per-step detail: [`quick-build/docs/sora-slow-path-gap.md`](../quick-build/docs/sora-slow-path-gap.md).

**Unmeasured, and the bigger question: the standard Gradle build.** Project build directories are on FUSE too, so every standard build pays the same toll on its class output, dex, and AAPT2 intermediates. It pays *less* than Quick Build did, because AGP's incremental tasks rewrite only what changed rather than the whole tree — but "less" is `[unmeasured]`. Given the corpus's on-device profile (library dexing was 56-66% of build time in the E8-E14 work, and dexing is a small-file-heavy stage), the exposure is plausibly large. **Nobody has measured it.**

**Also unmeasured: the low tiers.** All numbers here are the A56. The C107 (3.6 GB) and the itel A667L (1.9 GB) are 4-13x slower overall and were not re-measured; the finding should be *expected* to reproduce and be worse there, but that is `[inferred]`. Note the C107 has a separate, compounding problem — unattributed compile time that grows across a session, ADFA-4128 task #105 — which this does not explain. Its size is disputed: the "52%" that has been quoted for it does not reproduce from the 13 sub-step rows that exist, whose per-row shares are 1%, 3%, 59% and 36%. See [`quick-build/docs/perf-roadmap.md`](../quick-build/docs/perf-roadmap.md).

## Options, with their costs

1. **Move the Quick Build scratch tree to app-private storage.**

  `[measured on a56: -45% per edit]`. Smallest change, largest measured win, and it makes QB consistent with where CoGo already keeps Gradle's caches. Needs: a cleanup policy for stale per-project work dirs (they would otherwise accumulate in app-private storage forever, which matters most on the smallest tier, the 1.46 GB incar Q8), an audit of rebaseline/teardown paths that may assume the out dir sits under the project, a collision-safe directory key, and tests. Tracked as ADFA-4128 task #101.
2. **Move project build directories off emulated storage.** Potentially a

  much broader win — it would touch every standard build, not just Quick Build — but it is a genuine product tradeoff, not a free optimization:
  - build outputs stop being visible to the user's file manager and over MTP;
  - outputs no longer travel with the project folder when it is copied or

    shared, which for an offline-first, sneakernet-friendly product is a real loss;
  - CoGo takes on lifecycle responsibility for output dirs it did not own

    before (orphan cleanup on project delete/rename, storage accounting);
  - APK/artifact export paths would need an explicit copy-out step.

  **Measure the exposure before deciding.** A standard-build A/B with the build directory relocated, on one mid and one low device, would size the prize; today the number is unknown. Task #106.
3. **Write less, regardless of filesystem.** Incremental dexing and not

  re-stripping unchanged classes (both ADFA-4128 task #102) reduce the file count itself. These compound with option 1 and are the only ones that also help if a future Android makes this path slower again. Sequencing, effort and risk for all of these: [`quick-build/docs/perf-roadmap.md`](../quick-build/docs/perf-roadmap.md).

## What to take away

- Any on-device tool that rewrites many small files under the user's project

  folder is paying a ~50x per-file toll. Prefer app-private storage for anything that is *machinery* rather than *user-visible artifact* — which is already CoGo's convention for the Gradle and Kotlin caches, plugins, and the bundled toolchain.
- Before optimizing an on-device build stage, check which filesystem it writes

  to. A stage that looks CPU-bound in a profile may be waiting on FUSE.
- Provenance discipline matters here: the Quick Build numbers are measured, the

  standard-build exposure is not, and they should not be quoted with the same confidence.
