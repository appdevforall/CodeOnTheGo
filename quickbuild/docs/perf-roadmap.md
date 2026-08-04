# Performance roadmap

Where the remaining Quick Build latency lives, and which levers are worth pulling next. Quick
Build already runs a median 3.45x faster than an incremental standard build, over 78 edits across
23 corpus apps `[measured on a56]`. Lever 1 has shipped; the rest are ranked by ROI.

| #   | Fix                                               | Affects             | Payoff per warm edit                          | Effort | Risk | Status       |
| --- | ------------------------------------------------- | ------------------- | --------------------------------------------- | ------ | ---- | ------------ |
| 1   | Move the daemon scratch tree off emulated storage | All apps            | -36% subset-median, 6 apps `[measured on a56]` | S      | M    | **SHIPPED**  |
| 3a  | Reuse the javac file manager                      | Apps with Java      | ~0.5-1.0 s `[inferred]`                       | S      | L    | open         |
| 3b  | Per-changed-file javac                            | Apps with Java      | ~1.5-3.2 s more `[inferred]`                  | M      | M    | open         |
| 2   | Incremental dexing                                | All apps            | 2.1-4.6 s                                     | L      | M    | open         |
| 4   | Narrow "Java ABI moved -> recompile all Kotlin"   | Mixed Java + Kotlin | 14.9 s, ABI-change edits only                 | L      | M    | open         |

Sequencing:

- Lever 1 had to land first because it masked the win from 2 and 4.
- 3a gates 3b: disjoint risks - stale cache vs stale bytecode - so a bug stays attributable.
- Lever 4 is last on purpose. It is blocked on the same Build Tools API limitation its own KDoc
  documents, and its target should be measured after 1-3 land.
- Lever 5 (stop re-stripping unchanged classes) is not scheduled separately. Lever 1 already took
  it from 4.7-5.5 s to 0.17-0.33 s `[measured on a56]`, and it shares lever 2's cache key, so fold
  it into lever 2.

Levers 3a/3b are designed in [`incremental-javac-design.md`](incremental-javac-design.md).

## Where a warm edit goes

Reference workload: `sora-editor-full` (288 sources: 214 `.java` + 74 `.kt`, 464 classes /
1.46 MB) - the corpus's worst Quick Build case, and the only app where it lost to a standard
build. Warm edit, ms, pre-lever-1 `[measured on a56]`:

| edit            | total | javac | kotlinc | strip | d8   | policy+walks |
| --------------- | ----- | ----- | ------- | ----- | ---- | ------------ |
| Java body       | 14718 | 3983  | 659     | 5492  | 3104 | 883          |
| Kotlin body     | 14922 | 2849  | 3447    | 4659  | 2421 | 1058         |
| Java ABI change | 28055 | 2677  | 16377   | 4776  | 2268 | 1465         |

- **javac** compiles all the project's `.java` sources in-process; no incremental mode today.
- **kotlinc** is the Build Tools API incremental compile - just the edited `.kt` files, or every
  Kotlin source if a Java ABI moved.
- **strip** clears `ACC_FINAL` on a mirror of every `.class` so generated proxies can extend user
  classes. It rewrites the whole tree each time, which is why it dominated on FUSE.
- **d8** dexes the whole stripped tree every time.
- **policy+walks** is two `Files.walk` passes plus the ASM header parse that picks restart /
  recreate / rebuild. Not additive with `total` - it mixes a daemon-side and host-side span.

## Ruled out

- *"The Java-ABI gate fails open, recompiling all Kotlin on a Java edit."* False - a Java body edit
  measured `nKotlinToCompile=0` `[measured on a56]`.
- *"Non-incremental dexing is the dominant cost."* Half right - dex is 48-60% of the edit, but most
  of that was strip's file I/O, not dex compute.
- *"javac is the bottleneck."* No - 25-36% of a warm edit today (19-27% before the storage move).
  `compileMs` alone reads higher because it excludes dex.
- The tool timings cover only about half a warm edit, so read the analytics event's unaccounted
  **residual** field alongside them.

## Settled - do not re-derive

- Daemon IPC is free: 20-60 ms on multi-second calls `[measured on a56]`.
- The "53 s per edit" figure was never a per-edit cost. It was the session's first build, which now
  runs as a background warm compile before the user can save.
- The warm compile is what makes the *first* save fast, and it is worth **6.1x** on that save:
  **1.9 s warmed vs 11.5 s unwarmed**, almost all of it cold `kotlinc`. Matched on/off A/B, 3
  trials per arm, one build, `hello-kotlin` (`corpus/results/20260728T153938Z-seed-ab/`)
  `[measured on a56]`. Tap-to-`Ready` is unchanged, because the warm compile starts after `Ready`.

## Not covered here

- **The standard Gradle build's own exposure to the same filesystem toll** - project `build/` dirs
  are on emulated storage too. `[unmeasured]`; tracked separately in Jira.
- **The low device tiers** - every number here is the A56; the C107 and 1.9 GB tier are 4-13x
  slower overall and were not re-measured.
- **`readyou`** - a pure-Kotlin 6-file module measuring 13.7 s / 15.2 s before dropping to 2.9 s
  `[measured on a56]`. No javac, no large class tree; nothing above explains it.

Evidence: `20260728T172912Z-sora-deepdive/` and
`results/analysis/offfuse-comparison-2026-07-31.md` in `CodeOnTheGo-build-benchmark`.
