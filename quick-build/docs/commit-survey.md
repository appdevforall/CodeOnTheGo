# Information: How often Quick Build's live reload path applies to real commits (the headline is optimistic)

**Status**: complete and reproducible, but a proxy rather than a metric. Its classifier has fallen behind the shipped one since the run.

**Provenance**: every number is `[measured on host]` — a static classification of GitHub commit metadata, run on the Mac Mini. Nothing here was measured on a device. `[inferred]` marks a judgement about what a number implies.

## Summary

- Across 99 repos and 3,126 human-authored commits, **72.4% of commits would take Quick Build's live reload path** under the watch scope the device actually ships. The strict per-file reading, which assumes the device sees every file in the repo, is 41.3%.
- Two things make that headline optimistic:
  - **Most of the gain is invisible edits, not reloads.** 708 of the 970 commits that flip to the live reload path under watch scope are device no-ops — the watcher never sees their files, so nothing happens on screen. Only **49.5% take a route that actually reloads something**.
  - **The survey's classifier was behind the shipped one; the boundary rule is now measured.** Re-classifying the cached commit file lists with the shipped app-module rule takes live-reloadable commits in multi-module repos from **687 (43.8%) to 393 (25.0%)** — **294 flips, so 42.8% of what the shape-only classifier called quick was wrong there**. Of the 1,570 commits in repos whose app module resolved, **31.6% edit inside the app module** (live reload path today) and **34.4% touch source outside it** (the Level 2 population); 308 commits in 10 unresolvable repos are excluded, not assumed. Evidence: `corpus/results/analysis/app-module-boundary.json` `[measured 2026-07-29]`. The remaining gap is annotation-processor escalation, which is not reachable from commit metadata — it needs a real proxy app build's baseline. Superseded estimate, kept for the record: The app-module boundary rule landed after the run; applying it takes 72.4% to **~49.9%**, and the reload share to **~27.0%**. A second rule (annotation-processor escalation) pushes both lower by an amount nobody has bounded.
- Per-repo variation is enormous — median 42.3%, repos at both 0% and 100%. This is a population number, not a typical-repo number.
- **Believe ~50% live-reload and ~27% actually-reloads, as ceilings — not 72.4% — and use them to rank gaps rather than as a product metric.** A commit is not an edit.

## What the survey found

Across 99 repos and 3,126 human-authored commits `[measured on host]`:

| reading            | result                            | what it assumes                                              |
| ------------------ | --------------------------------- | ------------------------------------------------------------ |
| strict per-file    | **41.3%** live-reloadable (1,292) | the device sees every file in the repo                       |
| device watch scope | **72.4%** (2,262)                 | the device sees only `<module>/src/**` plus gradle config — which is what actually ships |
| of which sound     | **70.8%**                         | excluding 49 commits (1.6%) that would live-reload past a real build input |
| multi-module       | 60.6% of repos, 60.1% of commits  | 60 of 99 repos have more than one module — by the survey's definition, not the shipped one |

Per-repo variation is enormous `[measured on host]`: median 42.3% live-reloadable, interquartile range 12.9%-60.0%, with repos at both 0% and 100%. The headline is a population number, not a typical-repo number.

**Use these numbers to rank gaps, not to state a product metric.**

- "Build config is the biggest remaining fallback cause" is sound from this data.
- "72% of developer edits live-reload" is not — a commit is not an edit:
  - Developers save many times per commit, and it is saves that hit the watcher.
  - The saves in between are almost by construction more likely to be plain code edits, because the dependency bump and the manifest change get folded into the same commit as the code they enable.
  - GitHub does not record saves at all.

## What 72.4% does not mean

**72.4% does not mean 72% of commits live-reload your change.** 970 commits flip from fallback to the live reload path once you account for what the watcher cannot see, but **708 of those 970 flip to ****`NoOp`** — their only changed files are invisible, so on a device nothing happens at all.

The genuinely useful flips `[measured on host]`:

| route flipped to                   | commits |
| ---------------------------------- | ------- |
| `NoOp` (nothing happens on device) | 708     |
| `CodeOnly`                         | 176     |
| `CodeAndResources`                 | 59      |
| `ResourcesOnly`                    | 25      |
| `AssetsOnly`                       | 2       |

The fair split of all 3,126 commits `[measured on host]`:

| outcome                                       | commits | share     |
| --------------------------------------------- | ------- | --------- |
| takes a route that actually reloads something | 1,547   | **49.5%** |
| device no-op                                  | 715     | **22.9%** |
| still falls back                              | 864     | **27.6%** |

The no-op bucket is the 708 flips plus the 7 commits the strict reading already scored `NoOp`; an earlier revision omitted those 7 from the bucket while subtracting them from the reload bucket, so its three shares summed to 99.8%.

## Why every number is a ceiling

The Python classifier mirrors the shipped Kotlin one (`quick-build/.../domain/ChangeClassifier.kt`) rule for rule, and `--selftest` passes 39 of 39 cases from `ChangeClassifierTest` `[measured on host]`. But it mirrors the **path-shape rules only**, and the Kotlin classifier has since grown two rules outside that shape. Both send commits to fallback that the survey scores as live-reload:

- **`annotationImpact.escalation(...)`** (`ChangeClassifier.kt:111`)
  - On a project with a KSP/kapt processor, a code change that could have moved generated code escalates to a proxy app rebuild.
  - Content-aware, so no path-only mirror can model it.
  - It landed 2026-07-24, **before** this run, and the survey does not account for it.
- **`fastPathRoots`**** / ****`NON_APP_MODULE_SOURCE_CHANGED`** (`ChangeClassifier.kt:78-82`)
  - Any code, resource or asset edit outside the app module's `src` root takes a proxy app rebuild, because the live reload path incrementally compiles only the app module.
  - It landed 2026-07-27 17:53 PDT, **after** the 06:02 UTC survey run.
  - This bites hardest in the 60 multi-module repos: every commit the survey counts as code-in-a-library-module would take a proxy app rebuild on today's build.
  - Unlike the annotation rule, this one's effect is **arithmetically computable** from the artifacts already on disk — see below.

### Sizing the app-module-boundary correction

[`multi-module.md`](multi-module.md)'s category 2 is exactly the set this rule moves: commits that are correct today but would now take a proxy app rebuild. It is **37.4% of the 1,878 multi-module commits = 702 commits**. Subtracting them from the survey's own totals `[measured on host, arithmetic on host]`:

| reading                             | as surveyed   | with the rule applied |
| ----------------------------------- | ------------- | --------------------- |
| watch-scope live reload             | 72.4% (2,262) | **49.9% (1,560)**     |
| of which actually reloads something | 49.5% (1,547) | **27.0% (845)**       |

That is a **bound, not a point estimate**, and it is loose in both directions:

- **Loose high — this bound is wrong and should not be relied on.** This claimed the boundary rule disables itself in a repo with no directory literally named `app`, leaving those commits on their old route. **That is false.** `QuickBuildProjectLayout.kt:137` returns `listOf(File(appModuleDir, "src"))` — never empty — and `appModuleDir` is the module the IDE selected (`GradleQuickBuildProvisioner.kt:80,138`), not a directory name; `File(projectRoot, "app")` is only a constructor default used by tests. So no such escape hatch exists, the boundary applies in every project, and the corrected headline is likely **lower** than the ~49.9% quoted above, not higher `[measured, code read 2026-07-28]`.
- **Loose low.** Category 2's own split between app-module and library-module code is itself an assumption of the design doc's value table, not a measurement.

**Do not quote 72.4% without this correction attached.**

### The biases that cannot be sized

- **`annotationImpact`**** is currently unbounded.** It is content-aware, it fires only in projects carrying a KSP or kapt processor, and neither the survey nor the multi-module artifact records which repos those are. Bounding it needs one cheap addition to the next run — a per-repo "declares a KSP/kapt plugin" flag, readable from the same repo trees the module scan already walks — which would at least cap the affected share.
- **Truncated file lists.** Commit file lists are capped by the GitHub REST API at 300 files, and a hidden gradle file past the cap could only move a commit from live reload to fallback. A `truncated` flag is recorded per commit but **nothing reads it**, so the bias is unmitigated.
- **Routes, not outcomes.** A `CodeOnly` verdict means "Quick Build would try", not "would succeed".
- **Project shape ignored.** A commit in a project Quick Build cannot provision at all still classifies as `CodeOnly`.

Against those, one conservative bias: the unsupported-file rule catches documentation, CI config, screenshots, ProGuard rules and lockfiles equally, and most of those cannot change an app's behaviour. That is the entire gap between the strict and watch-scope readings.

**Re-running the survey against the current classifier is owed before any of these percentages is quoted again** — and the script's docstring still claims "there is no content-dependent decision to conservatively approximate", with stale Kotlin line references to fix while doing it.

## How a commit is classified

Each changed path maps to one file kind, by path shape only:

| kind          | rule                                                         |
| ------------- | ------------------------------------------------------------ |
| gradle config | filename in `build.gradle[.kts]`, `settings.gradle[.kts]`, `gradle.properties`, `local.properties`; any `*.toml` under a `gradle/` dir; `gradle-wrapper.properties` under a `wrapper/` dir |
| manifest      | filename `AndroidManifest.xml`, anywhere                     |
| resource      | under both a `src` dir and a `res` dir                       |
| asset         | under both a `src` dir and an `assets` dir                   |
| code          | `.kt` or `.java`, anywhere                                   |
| unsupported   | everything else                                              |

The commit's whole file set then decides the route: **any** gradle, manifest or unsupported file forces `FullGradleBuild`. Otherwise the commit takes the cheapest route its remaining kinds allow.

Two consequences worth arguing with:

- **All-or-nothing per commit.** One `.md` file in an otherwise pure code commit sends the whole commit to fallback. That is faithful to the shipped classifier, and is why the strict number is as low as it is.
- **`res/`**** and ****`assets/`**** only count under a ****`src`**** dir.** A resource outside a module `src` tree classifies as unsupported.

Under watch scope, a fallback commit flips only when *every* blocking file is invisible:

- Any gradle-config or manifest blocker keeps it fallback — both are watched.
- So does any unsupported file under `src/`: watched, and genuinely unsupported (a `.png` misplaced in `src`, a native `.cpp`).

### Risky flips: 49 commits, 1.6%, that would live-reload past a real build input

A **risky flip** is one whose out-of-scope blockers are plausibly real build inputs the watcher misses — `.pro`, `.properties`, `.toml`, `.cmake`, `.c`, `.h`, `.gradle`, `.kts`, `.lock`, `.aar`, `.jar`, `.so` outside `src/`. There the device would live-reload while a full build could legitimately produce something different: the app keeps running with stale ProGuard rules or a stale native library.

**49 of them, 1.6% of all commits** `[measured on host]`. That is not a rounding error — it is a correctness exposure, and it is why the watch-scope number should be quoted as "72.4%, of which 1.6 points are unsound".

## The corpus: 99 repos, 3,126 human commits, bots excluded

- The roster (`harness/pr-survey-repos.txt`) is a seed list plus a `gh search repos --topic android` sweep over Kotlin and Java, sorted by last update.
- Each candidate is verified to have a root `settings.gradle[.kts]` and `gradlew`.
- The survey walks each repo's default branch and takes the most recent 40 commits.
- Of 100 repos, one produced no default-branch commits, leaving **99 repos and 3,608 scanned commits**.
- Commits, not PRs — a PR squashes many edits and is a worse proxy for a save.

**Bots are excluded, and it changes the answer.** 482 of 3,608 scanned commits (13.4%) are bot-authored and dropped, leaving 3,126 `[measured on host]`. `is_bot_commit` checks:

- the linked GitHub account — login ending `[bot]`, account type `Bot`, or a login containing dependabot / renovate / greenkeeper / snyk / imgbot / weblate / transifex / crowdin / mergify / allcontributors / github-actions / semantic-release / release-please / pre-commit-ci / restyled;
- **and** the git identity in the commit object, so it catches bot commits signed under a plain name.

This matters because bot commits are overwhelmingly dependency and translation bumps — exactly the files that force a fallback — so leaving them in would have depressed the live-reload share with edits no human ever made.

## What still cannot live-reload: build config, by a wide margin

Of the 864 commits (27.6%) that remain fallback under watch scope, causes deduplicated per commit, so the column sums to more than 100% `[measured on host]`:

| cause                                          | commits | share of remaining fallback |
| ---------------------------------------------- | ------- | --------------------------- |
| build config (gradle files, version catalogs)  | 710     | 82.2%                       |
| manifest                                       | 146     | 16.9%                       |
| images and media under `src/`                  | 44      | 5.1%                        |
| non-resource XML                               | 38      | 4.4%                        |
| native code                                    | 29      | 3.4%                        |
| codegen inputs                                 | 19      | 2.2%                        |
| everything else (docs, scripts, CI, repo meta) | 46      | 5.3%                        |

Build config dominates, and the raw label counts say what most of it is: `build.gradle.kts` 477, version catalog 206, `gradle.properties` 81, `build.gradle` 71 — dependency and plugin-version bumps.

- That is the single largest remaining gap, and also the one where a fallback is arguably correct: a changed dependency really does need a real build.
- The open sub-question is whether a *version-only* catalog bump could be handled more cheaply than a full proxy app rebuild, which nothing here answers.

For reference: shipping a docs/CI/repo-metadata ignore list would take the **strict** reading from 41.3% to 59.1%. That list is not shipped and does not need to be — the device watcher already achieves the same effect and more, which is why watch scope is the reading that describes shipped behaviour.

## Most repos are multi-module, but cross-module edits are a minority of their commits

60 of 99 repos have more than one module, holding 1,878 of 3,126 commits. The survey's definition: module dirs = parents of a non-root `build.gradle[.kts]`, minus any whose top-level segment is `buildSrc`, `build-logic`, `build_logic` or `gradle`, and minus dot-dirs; "multi-module" means two or more such dirs.

**That is not the definition the feature uses**, so 60-of-99 is not a measurement of how many repos Quick Build would treat as multi-module. The shipped `QuickBuildProjectLayout.moduleDirs()` differs on four axes:

- it counts the project **root** as a module if the root holds a `build.gradle[.kts]`;
- it always includes the app module dir whether or not one exists;
- it does **not** exclude `buildSrc` / `build-logic` / `gradle`;
- it bounds the walk at `MODULE_SCAN_MAX_DEPTH = 4` while skipping `build/` and hidden dirs — where the survey has no depth bound at all.

The first three push the shipped count *up* relative to the survey and the depth bound pushes it *down*, so the direction of the net error is not even known. Recomputing under the shipped definition needs the `.cache/repo-trees` snapshots, which are not retained in the repo, so it is `[unmeasured]` until the survey is re-run. **The same caveat applies to every multi-module share below and in **[**`multi-module.md`**](multi-module.md)**.**

Within the 1,878, under watch-scope semantics `[measured on host]`:

| category                           | commits |
| ---------------------------------- | ------- |
| code in one module                 | 599     |
| code across multiple modules       | 153     |
| code plus resources in one module  | 91      |
| code plus resources across modules | 52      |
| resources only                     | 41      |
| assets only                        | 4       |
| still fallback                     | 463     |
| invisible no-ops                   | 475     |

**Only 376 commits (20% of the multi-module set) touch more than one module.** So multi-module support matters for the majority of real repos, but cross-module edits are a minority of the edits inside them. Design detail: [`multi-module.md`](multi-module.md).

This is the reading most affected by the app-module boundary rule that landed after the run: on today's build an edit to a library module's `src` takes a proxy app rebuild rather than live-reloading, so some share of those 599 single-module and 153 cross-module code commits would not take the live reload path at all. The design doc's value table splits that share as its category 2, **37.4% of the 1,878 = 702 commits**, which is what "Sizing the app-module-boundary correction" above subtracts. That split is an assumption of the value table, not a measurement, so the corrected headline is a bound rather than a number.

## Making this a real metric needs device analytics, not GitHub

The survey is a static proxy and will stay one. Turning "what share of edits live-reload" into a measurement needs data from the device, not from GitHub. The identified route is **encounter-rate analytics**: the session already emits structured events per build, so counting routes taken per session would answer the question directly, on the devices and project shapes we actually ship to.

Until that exists, quote this survey for what it supports:

- the ranking of gaps — build config first, manifest second, everything else a long tail;
- the fact that most real repos are multi-module;
- the fact that roughly 1.6% of commits would take the live reload path the shipped watcher cannot fully justify.

## Where the code and outputs live

- Scripts: `harness/pr_quickbuild_survey.py`, `harness/watch_scope_report.py`, `harness/multimodule_report.py` in the `CodeOnTheGo-build-benchmark` repo.
- Outputs: `results/commit-survey-latest/` and `results/multimodule-analysis/`.
