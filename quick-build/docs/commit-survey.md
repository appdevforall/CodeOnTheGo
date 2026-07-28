# The commit survey: how it works and what it found

Status: complete and reproducible, but a proxy rather than a metric, and its
classifier has fallen behind the shipped one since the run. **Every percentage
here is a ceiling, not an estimate.**

Provenance: every number is `[measured on host]` — a static classification of
GitHub commit metadata, run on the Mac Mini. Nothing here was measured on a
device. `[inferred]` marks a judgement about what a number implies.

Source: `harness/pr_quickbuild_survey.py`, `harness/watch_scope_report.py`,
`harness/multimodule_report.py` in the `test_app_corpus` repo. Outputs under
`results/commit-survey-latest/` and `results/multimodule-analysis/`.

## What it found

Across 99 repos and 3,126 human-authored commits `[measured on host]`:

| reading | result | what it assumes |
|---|---|---|
| strict per-file | **41.3%** quick-buildable (1,292) | the device sees every file in the repo |
| device watch scope | **72.4%** (2,262) | the device sees only `<module>/src/**` plus gradle config — which is what actually ships |
| of which sound | **70.8%** | excluding 49 commits (1.6%) that would fast-path past a real build input |
| multi-module | 60.6% of repos, 60.1% of commits | 60 of 99 repos have more than one module |

**72.4% does not mean 72% of commits hot-reload your change.** 970 commits flip
from fallback to fast path once you account for what the watcher cannot see, but
**708 of those 970 flip to `NoOp`** — their only changed files are invisible, so
on a device nothing happens at all. The genuinely useful flips are 176
`CodeOnly`, 59 `CodeAndResources`, 25 `ResourcesOnly`, 2 `AssetsOnly`. The fair
split of all 3,126 commits: **49.5% take a route that actually reloads something,
22.7% are device no-ops, 27.6% still fall back.**

Per-repo variation is enormous — median 42.3% quick-buildable, interquartile
range 12.9%-60.0%, with repos at both 0% and 100%. The headline is a population
number, not a typical-repo number.

**Use these numbers to rank gaps, not to state a product metric.** "Build config
is the biggest remaining fallback cause" is sound from this data. "72% of
developer edits hot-reload" is not — a commit is not an edit. Developers save many
times per commit and it is saves that hit the watcher; the saves in between are
almost by construction more likely to be plain code edits, because the dependency
bump and the manifest change get folded into the same commit as the code they
enable. GitHub does not record saves at all.

## Why every number is a ceiling

The Python classifier mirrors the shipped Kotlin one
(`quick-build/.../domain/ChangeClassifier.kt`) rule for rule, and
`--selftest` passes 39 of 39 cases from `ChangeClassifierTest`
`[measured on host]`. But it mirrors the **path-shape rules only**, and the Kotlin
classifier has since grown two rules outside that shape. Both send commits to
fallback that the survey scores as fast-path:

- `annotationImpact.escalation(...)` (`ChangeClassifier.kt:111`) — on a project
  with a KSP/kapt processor, a code change that could have moved generated code
  escalates to a rebaseline. Content-aware, so no path-only mirror can model it.
  It landed 2026-07-24, **before** this run, and the survey does not account for
  it.
- `fastPathRoots` / `NON_APP_MODULE_SOURCE_CHANGED` (`ChangeClassifier.kt:78-82`)
  — any code, resource or asset edit outside the app module's `src` root
  rebaselines, because the quick path incrementally compiles only the app module.
  It landed 2026-07-27 17:53 PDT, **after** the 06:02 UTC survey run. This bites
  hardest in the 60 multi-module repos: every commit the survey counts as
  code-in-a-library-module would rebaseline on today's build.

Three further optimistic biases: commit file lists are capped by the GitHub REST
API at 300 files and a hidden gradle file past the cap could only move a commit
from fast-path to fallback (a `truncated` flag is recorded per commit but
**nothing reads it**, so the bias is unmitigated); the survey classifies **routes,
not outcomes**, so a `CodeOnly` verdict means "Quick Build would try", not "would
succeed"; and it ignores project shape, so a commit in a project Quick Build
cannot provision at all still classifies as `CodeOnly`.

Against those, one conservative bias: the unsupported-file rule catches
documentation, CI config, screenshots, ProGuard rules and lockfiles equally, and
most of those cannot change an app's behaviour. That is the entire gap between
the strict and watch-scope readings.

**Re-running the survey against the current classifier is owed before any of
these percentages is quoted again** — and the script's docstring still claims
"there is no content-dependent decision to conservatively approximate", with
stale Kotlin line references to fix while doing it.

## How a commit is classified

Each changed path maps to one file kind, by path shape only:

| kind | rule |
|---|---|
| gradle config | filename in `build.gradle[.kts]`, `settings.gradle[.kts]`, `gradle.properties`, `local.properties`; any `*.toml` under a `gradle/` dir; `gradle-wrapper.properties` under a `wrapper/` dir |
| manifest | filename `AndroidManifest.xml`, anywhere |
| resource | under both a `src` dir and a `res` dir |
| asset | under both a `src` dir and an `assets` dir |
| code | `.kt` or `.java`, anywhere |
| unsupported | everything else |

The commit's whole file set then decides the route: **any** gradle, manifest or
unsupported file forces `FullGradleBuild`. Otherwise the commit takes the
cheapest route its remaining kinds allow.

Two consequences worth arguing with. It is **all-or-nothing per commit** — one
`.md` file in an otherwise pure code commit sends the whole commit to fallback,
which is faithful to the shipped classifier and is why the strict number is as low
as it is. And `res/` and `assets/` only count **under a `src` dir**; a resource
outside a module `src` tree classifies as unsupported.

Under watch scope a fallback commit flips only when *every* blocking file is
invisible: any gradle-config or manifest blocker keeps it fallback (both are
watched), as does any unsupported file under `src/` (watched, and genuinely
unsupported — a `.png` misplaced in `src`, a native `.cpp`).

A **risky flip** is one whose out-of-scope blockers are plausibly real build
inputs the watcher misses — `.pro`, `.properties`, `.toml`, `.cmake`, `.c`, `.h`,
`.gradle`, `.kts`, `.lock`, `.aar`, `.jar`, `.so` outside `src/`. There the device
would fast-path while a full build could legitimately produce something different:
the app keeps running with stale ProGuard rules or a stale native library. **49 of
them, 1.6% of all commits.** That is not a rounding error — it is a correctness
exposure, and it is why the watch-scope number should be quoted as "72.4%, of
which 1.6 points are unsound".

## The corpus

The roster (`harness/pr-survey-repos.txt`) is a seed list plus a
`gh search repos --topic android` sweep over Kotlin and Java, sorted by last
update; each candidate is verified to have a root `settings.gradle[.kts]` and
`gradlew`. The survey walks each repo's default branch and takes the most recent
40 commits. Of 100 repos, one produced no default-branch commits, leaving 99 and
3,608 scanned commits. Commits, not PRs — a PR squashes many edits and is a worse
proxy for a save.

**Bots are excluded, and it changes the answer.** 482 of 3,608 scanned commits
(13.4%) are bot-authored and dropped. `is_bot_commit` checks both the linked
GitHub account (login ending `[bot]`, account type `Bot`, or a login containing
dependabot / renovate / greenkeeper / snyk / imgbot / weblate / transifex /
crowdin / mergify / allcontributors / github-actions / semantic-release /
release-please / pre-commit-ci / restyled) **and** the git identity in the commit
object, so it catches bot commits signed under a plain name. This matters because
bot commits are overwhelmingly dependency and translation bumps — exactly the
files that force a fallback — so leaving them in would have depressed the
fast-path share with edits no human ever made.

## What still cannot quick-build

Of the 864 commits (27.6%) that remain fallback under watch scope, causes
deduplicated per commit, so the column sums to more than 100%
`[measured on host]`:

| cause | commits | share of remaining fallback |
|---|---|---|
| build config (gradle files, version catalogs) | 710 | 82.2% |
| manifest | 146 | 16.9% |
| images and media under `src/` | 44 | 5.1% |
| non-resource XML | 38 | 4.4% |
| native code | 29 | 3.4% |
| codegen inputs | 19 | 2.2% |
| everything else (docs, scripts, CI, repo meta) | 46 | 5.3% |

Build config dominates, and the raw label counts say what most of it is:
`build.gradle.kts` 477, version catalog 206, `gradle.properties` 81,
`build.gradle` 71 — dependency and plugin-version bumps. That is the single
largest remaining gap, and also the one where a fallback is arguably correct: a
changed dependency really does need a real build. The open sub-question is whether
a *version-only* catalog bump could be handled more cheaply than a full
rebaseline, which nothing here answers.

For reference: shipping a docs/CI/repo-metadata ignore list would take the
**strict** reading from 41.3% to 59.1%. That list is not shipped and does not need
to be — the device watcher already achieves the same effect and more, which is why
watch scope is the reading that describes shipped behaviour.

## On multi-module

60 of 99 repos have more than one module (module dirs = parents of a non-root
`build.gradle[.kts]`, minus any whose top-level segment is `buildSrc`,
`build-logic`, `build_logic` or `gradle`, and minus dot-dirs), holding 1,878 of
3,126 commits. Within them, under watch-scope semantics: 599 commits are code in
one module, 153 code across multiple modules, 91 code plus resources in one
module, 52 code plus resources across modules, 41 resources only, 4 assets only;
463 stay fallback and 475 are invisible no-ops. **Only 376 commits (20% of the
multi-module set) touch more than one module.** So multi-module support matters
for the majority of real repos, but cross-module edits are a minority of the edits
inside them. Design detail: [`multi-module.md`](multi-module.md).

This is the reading most affected by the app-module boundary rule that landed
after the run: on today's build an edit to a library module's `src` rebaselines
rather than fast-paths, so some share of those 599 single-module and 153
cross-module code commits would not take a fast path at all. How large a share is
`[unmeasured]` until the survey is re-run.

## Making this a real metric

The survey is a static proxy and will stay one. Turning "what share of edits
hot-reload" into a measurement needs data from the device, not from GitHub. The
identified route is **encounter-rate analytics**: the session already emits
structured events per build, so counting routes taken per session would answer the
question directly, on the devices and project shapes we actually ship to.

Until that exists, quote this survey for what it supports: the ranking of gaps
(build config first, manifest second, everything else a long tail), the fact that
most real repos are multi-module, and the fact that roughly 1.6% of commits would
take a fast path the shipped watcher cannot fully justify.
