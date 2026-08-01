# Backlog Ticket: Slow emulated storage (FUSE) leads to 1.8x slowdown in Quick Build (and affects standard build too)

**Ready to file.** Project ADFA · type Bug · component build / storage. CoGo-wide, not ADFA-4128 — that work is where this was found, not what it is about.

**Provenance**: `[measured on a56]` = Samsung A56, 2026-07-28. Everything else is code reading or explicitly tagged `[inferred]` / `[unmeasured]`.

## Summary

`written by Bryan`, rest of this doc was written by Claude

- File operations via `FUSE` in user space (e.g. to the project folder) are about 50x slower than `f2fs` ops in the app private storage.
- The following processes currently use `FUSE` and get slowed down
  - Quick Build scratch files
  - Gradle project build output
  - User project sources
- For some of these, we should probably keep them in `FUSE`, if the user needs MTP access to be able to work with the files over USB
- **Decision: Do we want to move some of the processes above to app private-storage?**
  - In tests on a few apps, moving to private storage would typically save about ~45% (1-2s) on Quick Build incremental build time on a Samsung A56 (more seconds on a slower phone)

Rest was written by Claude but not edited very much...if you want more details see below.

## What was run

- **The filesystem cost on its own.** Copied one tree — 464 files / 1.46 MB / 40 dirs — with

  `cp -r` into each filesystem, best of 3 `[measured on a56]`.
- **A Quick Build A/B.** Moved only the daemon's scratch tree to app-private storage, changed

  nothing else, re-ran a warm edit on `sora-editor-full` (292 sources) and `medium-kotlin` (28 sources). Per-step timings from the daemon's own instrumentation. n=1 before, n=2 after, A56 only — a strong signal, not a distribution. Raw data: `corpus/results/20260728T172912Z-sora-deepdive/` in `CodeOnTheGo-build-benchmark`.
- **Controls.** kotlinc and d8 are compute-bound and should not move if this is a filesystem

  effect. They didn't.
- **Not run:** the standard Gradle build, and any device other than the A56.

## What is slow

Same tree, each filesystem `[measured on a56]`:

| target                       | time        |
| ---------------------------- | ----------- |
| `/data/local/tmp` (f2fs)     | **192 ms**  |
| `/storage/emulated/0` (FUSE) | **9985 ms** |

**52x**, and it is a per-file toll rather than a throughput factor — so the workload shape decides everything, and build tools are nothing but small files. Every `open`, `create`, `write`, `close`, `stat` and `mkdir` on `/storage/emulated` is a round trip out to a userspace daemon; on `/data` it is a kernel call. Android 11+ removed the in-kernel `sdcardfs` shim that made this cheap, so the cost is worst where CPU is scarcest — our target devices.

What that costs Quick Build. Its scratch tree (`classes/`, `ic/`, `cp-snap/`, `opened-classes/`, `dex/`) sits under the project root and is rewritten every save — roughly 800 FUSE round trips for a one-line edit. Moving only that tree `[measured on a56]`:

| workload                                   | before      | after                  |
| ------------------------------------------ | ----------- | ---------------------- |
| `sora-editor-full` warm edit (292 sources) | 14.7 s      | **8.1 s (-45%)**       |
| `medium-kotlin` warm edit (28 sources)     | 2.5 s       | **1.4 s (-45%)**       |
| strip pass                                 | 4.7-5.5 s   | 0.17-0.33 s (~20x)     |
| deploy-policy class-header pass            | 0.6-1.2 s   | 0.06-0.11 s (~10x)     |
| output-tree walks                          | 0.24-0.27 s | 0.03-0.05 s (~6x)      |
| kotlinc / d8 (compute-bound controls)      | —           | unchanged within noise |

Only the per-file I/O steps moved, and the small app gained the same 45% as the large one.

Everything CoGo owns internally — `GRADLE_USER_HOME`, Kotlin daemon state, the bundled toolchain, plugin unpack targets — is already on f2fs; what is on FUSE is what lives inside the user's project folder.

**The standard build pays the same toll, and nobody has measured it.** Build directories are on FUSE too, so every standard build pays on its class output, dex and AAPT2 intermediates. It pays *less* than Quick Build did — AGP's incremental tasks rewrite only what changed — but "less" is `[unmeasured]`. Library dexing was 56-66% of on-device build time in the E8-E14 work and is small-file-heavy, so the exposure is plausibly large `[inferred]`.

**The low tiers are also unmeasured** — the C107 (3.6 GB) and itel A667L (1.9 GB) should be expected to be worse `[inferred]`.

## Options, with their costs

1. **Move the Quick Build scratch tree to app-private storage** `[measured on a56: -45% per edit]`.

  Smallest change, largest measured win. Needs a cleanup policy for stale work dirs, an audit of rebaseline/teardown paths that assume the out dir sits under the project, a collision-safe directory key, and tests. ADFA-4128 task #101.
2. **Move project build directories off emulated storage.** Potentially much broader — it touches

  every standard build — but a genuine product tradeoff: outputs stop being visible in the file manager and over MTP, stop travelling with the project folder when it is copied or shared (a real loss for an offline-first product), CoGo takes on orphan cleanup and storage accounting, and APK export needs an explicit copy-out step. **Measure the exposure first** — a standard-build A/B with the build directory relocated, on one mid and one low device. Task #106.
3. **Write less, regardless of filesystem.** Incremental dexing and not re-stripping unchanged

  classes (ADFA-4128 task #102) cut the file count itself. These compound with option 1 and are the only ones that still help if a future Android makes this path slower again. Sequencing, effort and risk: [`quickbuild/core/docs/perf-roadmap.md`](../quickbuild/core/docs/perf-roadmap.md).

## Generalizable takeaway

Prefer app-private storage for anything that is *machinery* rather than *user-visible artifact*, and check which filesystem a build stage writes to before optimizing it — a stage that looks CPU-bound may be waiting on FUSE.
