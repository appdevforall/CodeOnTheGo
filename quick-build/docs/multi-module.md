# Decision: Library-module edits cost a 25 s rebuild and an install tap (multi-module projects otherwise work today)

**Status**: multi-module support is verified working on device as of 2026-07-28; the 07-22 framing of this gap was wrong and is corrected below. The "Level 2" fast path remains designed, unbuilt, and parked on this go/no-go.

**Provenance**: tags are mandatory — `[measured on a56]`, `[measured on host]`, `[inferred]`, `[unmeasured]`, `[assumed]`. Untagged prose is code reading.

**Evidence**: `quick-build/corpus/results/20260728T044815Z-watchscope-verify2-run4/` (the clean full pass) and `.../20260728T011901Z-watchscope-verify` (the first run, which failed on the install prompt). A56, CoGo dev build `C-d-0727-1820`.

## Summary

- Multi-module projects **work correctly today** — a two-module project provisions (`Ready gen=0` in 67 s) and an app-module edit hot-reloads in 2.55 s `[measured on a56]`. The 07-22 claim that they were rejected at setup was wrong.
- What is slow is everything **outside** the app module: any such edit routes to a full rebaseline — 25 s edit->Ready `[measured on a56]`, plus an Android install-confirm tap, on **every** library edit.
  - Affected: library-module code and resources, any module's `build.gradle[.kts]`, `settings.gradle`, the version catalog.
  - Estimated reach: ~37% of multi-module commits, ~22% of all surveyed commits `[measured on host, inferred combination]`.
- The constraint: Level 2 is a **latency** improvement on edits that already behave correctly, not a correctness unblock — and its value rests on `[inferred]` ratios over a commit-level proxy taken from GitHub repos, not CoGo users. It costs ~2-3 calendar weeks `[assumed]` plus standing harness/CI upkeep, and it competes with better-evidenced storage work in [`perf-roadmap.md`](perf-roadmap.md).
- **Decision: do we build Level 2 this cycle?** Recommendation: no-go — take L2.0 plus analytics and revisit with field data.
  - Payoff if we do build it: ~25 s rebaseline + install tap -> projected ~2-3 s hot reload `[inferred]` on that ~37%, and the team can finally dogfood Quick Build on CoGo's own ~88-module codebase.

## Recommendation: no-go this cycle

Multi-module projects work correctly today — that is the correction below, and it removes the correctness argument for Level 2 entirely. What remains is a latency win on ~37% of multi-module commits, resting on `[inferred]` ratios and a commit-level proxy.

What to take instead:

- **L2.0, the differential-correctness harness.** It stands alone — it retroactively protects single-module Quick Build too.
- **The analytics.** Log how often users actually hit a non-app-module edit and which case it is. Today's shares come from GitHub repos, not CoGo users, and CoGo's users may not write multi-module apps at all. One release of field data would replace the weakest input in the whole value case, for a day of work rather than three weeks. (Framed in the earlier draft as "the cheap third option, if the team defers".)

Why the storage work wins if only one thing lands. Level 2 competes with the measured performance work in [`perf-roadmap.md`](perf-roadmap.md), and that work is better-evidenced — moving the daemon scratch tree off emulated storage measured -45% on **both** apps it was tried on `[measured on a56]`:

| App                | Sources | Before | After |
| ------------------ | ------- | ------ | ----- |
| `sora-editor-full` | 292     | 14.7 s | 8.1 s |
| `medium-kotlin`    | 28      | 2.5 s  | 1.4 s |

- One edit each, n=1 before and n=2 after, A56 only.
- Two apps an order of magnitude apart landing on the same 45%, with an independent mechanism measurement behind it, is a strong signal — but it is two apps, not "every warm edit on every app size".
- Against Level 2's `[inferred]` win on a survey-estimated share of commits, the storage fix still has the stronger evidence.

## The correction: the gap was a silent-stale bug, not a rejection

- The 07-22 status said multi-module projects were **rejected at Quick Build setup**. That is not what the code does and not what the device does.
- A two-module project (`:app` + `:lib`) provisions and Quick-Builds fine — `Ready gen=0` in 67 s, and an app-module edit hot-reloads in 2.55 s `[measured on a56]`.
- The real gap was worse than a rejection, and quieter: **library-module edits were invisible to the watcher.**
  - The watcher only watched the app module's `src`, so a save in `:lib` fired no event at all — no build, no reload, no fallback, no error.
  - The running app kept serving the old library code while the editor showed the new source.
  - That is a silent-stale bug, the one failure class the whole feature exists to prevent, not a missing capability. A rejection would have been the safe version of this; nobody would have been misled.
- Fixed in `cc9bedea7` and device-verified.
- Why it matters for the go/no-go: Level 2 is a **latency** improvement on edits that already behave correctly, not a correctness unblock.

## What happens today, precisely

Three scopes, in `data/QuickBuildProjectLayout.kt`:

- **Watched roots** — every module's `src`.
  - Modules are discovered by a shallow filesystem walk from the project root (`moduleDirs()`).
  - Any directory holding a `build.gradle[.kts]` counts.
  - `build/` and hidden dirs are skipped.
  - Bounded at `MODULE_SCAN_MAX_DEPTH = 4`.
- **Watched files** —
  - `settings.gradle[.kts]`
  - `gradle.properties`
  - `gradle/libs.versions.toml`
  - every module's `build.gradle[.kts]`
- **Fast-path scope** (`fastPathScope()`) — `app/src` only. The app module is the directory literally named `app`.

Routing:

- `ChangeClassifier` routes any watched code/resource/asset change **outside** the fast-path scope to `BuildRoute.FullGradleBuild` with `InvalidationReason.NON_APP_MODULE_SOURCE_CHANGED`.
- An empty fast-path scope disables the boundary entirely, which is how single-module projects keep their previous behaviour.

| Edit                                                         | Route                                | Cost                                                         |
| ------------------------------------------------------------ | ------------------------------------ | ------------------------------------------------------------ |
| App-module code / resources / assets                         | fast path                            | 2.55 s save->live `[measured on a56]`                        |
| Any other module's code / resources                          | rebaseline (full Gradle setup build) | 25 s edit->Ready, including the reinstall `[measured on a56]` |
| Any module's `build.gradle[.kts]`, `settings.gradle`, version catalog | rebaseline                           | as above                                                     |

The scan errs toward watching **more** than it needs, deliberately:

- Over-inclusion is harmless — a stray module's edit merely rebaselines.
- Under-inclusion resurrects the silent-drop bug.
- The depth-4 bound is the one place that can still under-include: a `:a:b:c:d`-deep module path watches less of its tail, and those edits fall back to the periodic mtime sweep. Rare shape, `[unmeasured]` in the wild.

## Device verification: all checks pass

`20260728T044815Z-watchscope-verify2-run4`, all checks PASS `[measured on a56]`. The project carries visible `APP-Vn` / `LIB-Vn` UI markers so staleness is observable on screen rather than inferred from logs:

| Check                         | Result                                              |
| ----------------------------- | --------------------------------------------------- |
| Two-module project provisions | Ready gen=0 in 67 s                                 |
| Baseline UI                   | `APP-V1`, `LIB-V1`                                  |
| Library edit is seen          | `invalidation reason=NON_APP_MODULE_SOURCE_CHANGED` |
| Library edit rebaselines      | `rebaseline ok=true`, edit->Ready 25 s              |
| Library edit reaches the app  | `APP-V1`, `LIB-V2`                                  |
| App edit fast-paths           | `reload_timeline gen=1`, save->live 2550 ms         |
| App edit reaches the app      | `APP-V2`, `LIB-V2`                                  |

- The never-stale invariant holds in both directions: the library edit visibly landed, and the app edit did not lose it.
- Protocol note for future readers: a successful rebaseline **resets the generation counter to 0** `[measured on a56]`, and post-rebaseline reloads resume at gen=1. Any consumer assuming monotonically increasing generations across a rebaseline will break.

## The install prompt makes the rebaseline path fragile

The first verification run **failed**, and for a more interesting reason than a missing tap `[measured on a56]`:

- A rebaseline reinstall needs the user to confirm an Android install dialog.
- If CoGo is backgrounded — exactly where the user is if they are looking at their running app — no dialog appears.
- The installer times out at 180 s and the edit is silently lost.
- A dialog-tapping harness cannot rescue this; there is nothing to tap.

Scope and mitigations:

- This is not multi-module-specific — it is the install-confirm gap owned by [`reliability-gaps.md`](reliability-gaps.md) defect 5, which also corrects the mechanism (the broadcast is deferred, not dropped; the failure is on our side).
- But multi-module makes it a **per-edit** concern rather than once-per-session, because every library edit rebaselines. That is a real argument for Level 2 that the latency numbers alone understate.
- `0ea640921` parks the session recoverable instead of dying to Idle.
- `fe949a9f0` re-prompts when CoGo next comes to the foreground.
- With CoGo foregrounded the dialog appears ~15 s after the edit and one tap confirms `[measured on a56]`.

## What Level 2 would add

- The design is written and reviewed; it is not restated here. See `docs/product/plans/2026-07-27_ADFA-4128_qb-multimodule/level2-design.md` in the wrapper repo (fuller prior revision archived beside it).
  - Its §4 is the implementation directive if the team says go.
  - Its §4.10 has the staging table and the L2.2 decision gate.
- In one line: emit the module dependency graph from the setup build, run one incremental-compile session per module, recompile the edited module and its dependents in dependency order, keep the edited module's resources live in the relink, and merge everything into one hot-apply.
- The test app and the reload protocol do not change — modules are a build-time concept only.
- **A correction now applied to the design doc.** It used to say "today Quick Build rejects multi-module projects at setup, so without this design their share is 0%" — the claim this device run overturned, and load-bearing for how its value table read. It now credits only category 2 to the design and defers to this page for device evidence.

## What the survey says it is worth

From [`commit-survey.md`](commit-survey.md) `[measured on host]`: 60 of 99 surveyed real Android repos are multi-module, holding ~60% of surveyed commits (1,878 classified). The design doc's value table splits those as:

| Category                                                     | Share | Status today                   |
| ------------------------------------------------------------ | ----- | ------------------------------ |
| 1 — existing machinery (no-op commits 25.3%, app-module code 11.1%) | 36.4% | **already works**              |
| 2 — added by Level 2 (library code 20.8%, multi-module saves 10.9%, code+resources 4.8%, resource values 0.7%, assets 0.2%) | 37.4% | correct today, but rebaselines |
| 3 — still a full build (gradle/manifest/processor 24.7%, resource shape changes 1.4%) | 26.1% | unchanged by Level 2           |

Read with the correction applied:

- The marginal value of Level 2 is **category 2 alone: ~37% of multi-module commits** — about 22% of all surveyed commits `[measured on host, inferred combination]`.
- Those edits move from a ~25 s rebaseline with an install prompt to a projected ~2-3 s hot reload `[inferred]`.
- Category 1's 36.4% is already delivered and is not Level 2's to claim.

Both the shares and the ratios carry real caveats:

- The shares are commit-level proxies, classified from changed-file lists by directory-name heuristics, not observed developer behaviour.
- The commit survey's own numbers are a ceiling — its classifier predates the app-module boundary rule.
- The ratios are `[inferred]`, anchored to measured single-module numbers, with nothing measured on a real modular app.
- The design doc's §4.10 makes measuring them the L2.2 gate, which is the right shape.

## The rest of the case, for the record

**What Level 2 buys beyond latency.**

- The install prompt is arguably the bigger half: a 25 s wait that also demands a tap, on every library edit, is a materially different product from one that does not.
- Multi-module is also the shape CoGo itself has (~88 modules), so the team cannot dogfood Quick Build on its own codebase until this lands.

**What it costs.**

- ~1 week of agent wall-clock plus 8-12 h of Bryan driving, roughly 2-3 calendar weeks at current pace `[assumed]`.
- The standing costs matter more than the build time:
  - a differential-correctness harness plus a CI job to maintain;
  - a standing dependency on the team-owned project-model builders, which means shared-model changes must consider Quick Build from then on.
- That last item needs team sign-off regardless of risk appetite.
