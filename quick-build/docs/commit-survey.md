# The commit survey: how it works and what it found

Status: complete, reproducible, and a proxy rather than a metric. Read the
caveat before the numbers.

Provenance: every number here is `[measured on host]` — a static classification
of GitHub commit metadata, run on the Mac Mini. Nothing in this doc was measured
on a device. `[inferred]` marks a judgement about what the number implies.

Source: `harness/pr_quickbuild_survey.py`, `harness/watch_scope_report.py` and
`harness/multimodule_report.py` in the `test_app_corpus` repo. Outputs:
`results/commit-survey-latest/commit-quickbuild-survey.json` and
`results/multimodule-analysis/multimodule-analysis.json`.

## The caveat, first

This survey answers "what share of **commits** in real Android apps would Quick
Build handle on its fast path". That is a proxy for the question we actually
care about — "what share of the **edits a developer wants to test immediately**
would Quick Build handle" — and the two are not the same.

A commit is not an edit. Developers save many times per commit, and it is the
saves that hit the watcher. The saves in between are, almost by construction,
more likely to be plain code edits than the commit that finally lands them,
because the dependency bump and the manifest change get folded into the same
commit as the code they enable. The survey cannot see saves at all: GitHub does
not record them.

So: **use these numbers to rank gaps, not to state a product metric.** "Gradle
files are the biggest remaining fallback cause" is a sound conclusion from this
data. "72% of developer edits hot-reload" is not.

## How a commit is classified

The classifier is a Python mirror of the shipped Kotlin one
(`quick-build/.../domain/ChangeClassifier.kt`), rule for rule, with each rule
citing the Kotlin line it mirrors. That is possible because the Kotlin
classifier is purely path-based — it never reads file content — so the mirror
is exact rather than approximate. `pr_quickbuild_survey.py --selftest` checks it
against the cases in `ChangeClassifierTest`; it passes 39 of 39
`[measured on host]`.

Each changed path maps to one file kind, by path shape only:

| kind | rule |
|---|---|
| gradle config | filename in `build.gradle[.kts]`, `settings.gradle[.kts]`, `gradle.properties`, `local.properties`; any `*.toml` under a `gradle/` dir (version catalogs); `gradle-wrapper.properties` under a `wrapper/` dir |
| manifest | filename `AndroidManifest.xml`, anywhere |
| resource | under both a `src` dir and a `res` dir |
| asset | under both a `src` dir and an `assets` dir |
| code | `.kt` or `.java`, anywhere |
| unsupported | everything else |

Then the commit's whole file set decides the route: **any** gradle, manifest, or
unsupported file forces `FullGradleBuild` (the fallback). If none is present,
the commit takes the cheapest route its remaining kinds allow —
`CodeAndResources`, `CodeOnly`, `ResourcesOnly`, `AssetsOnly`, or `NoOp`.

Two things follow from that shape and are worth arguing with:

- It is **all-or-nothing per commit**. One `.md` file in an otherwise pure code
  commit sends the whole commit to fallback, because `.md` is "unsupported".
  This is faithful to the shipped classifier, and it is also why the strict
  number is as low as it is.
- `res/` and `assets/` only count **under a `src` dir**. A resource outside a
  module `src` tree classifies as unsupported, not as a resource.

### Where the classifier is conservative, and where it is optimistic

Conservative (calls fallback where the device might cope):

- The unsupported-file rule catches documentation, CI config, screenshots,
  ProGuard rules and lockfiles equally. Most of those cannot change an app's
  behaviour at all, and on-device most of them are not even visible to the
  watcher — which is the entire gap between the strict and watch-scope readings
  below.
- Commit file lists are capped by the GitHub REST API at 300 files; a commit at
  the cap is flagged `truncated`. A hidden gradle file past the cap could only
  move a commit from fast-path to fallback, so truncation biases toward
  optimism, and the flag lets it be audited.

Optimistic (calls fast-path where the shipped implementation might not be):

- It classifies **routes**, not outcomes. A commit routed `CodeOnly` still has
  to compile, dex, and reload successfully on a real device; the corpus sweeps
  show real `CompileError` and `DeployFailure` rates that this survey does not
  model. A `CodeOnly` verdict means "Quick Build would try", not "Quick Build
  would succeed".
- It ignores project shape. A commit in a project Quick Build cannot provision
  at all still classifies as `CodeOnly` if its files are code.
- The watch-scope reading (below) deliberately assumes invisible means harmless,
  and quantifies the cases where that assumption is unsafe rather than removing
  them.

## The corpus

99 repos, 3,126 human-authored commits `[measured on host]`.

The roster (`harness/pr-survey-repos.txt`) is a seed list plus a `gh search
repos --topic android` sweep over Kotlin and Java, sorted by last update; each
candidate is verified to have a root `settings.gradle[.kts]` and `gradlew`
before it is kept. The survey then walks each repo's **default branch** and
takes the most recent 40 commits (median 37 per repo; 44 repos hit the cap). The
roster holds 100 repos; one produced no default-branch commits, leaving 99.
Commits, not PRs —
a PR squashes many edits and is a worse proxy for a save than a commit is.

**Bots are excluded, and it changes the answer.** 482 of 3,608 scanned commits
(13.4%) are bot-authored and dropped. `is_bot_commit` checks the linked GitHub
account (login ending `[bot]`, account type `Bot`, or a login containing one of
dependabot / renovate / greenkeeper / snyk / imgbot / weblate / transifex /
crowdin / mergify / allcontributors / github-actions / semantic-release /
release-please / pre-commit-ci / restyled) **and** the git identity baked into
the commit object, so it catches both App-account commits and bot commits signed
under a plain name. This matters because bot commits are overwhelmingly
dependency and translation bumps — exactly the files that force a fallback — so
leaving them in would have depressed the fast-path share with edits no human
ever made.

Per-repo results vary enormously: median 42.3% quick-buildable, interquartile
range 12.9%-60.0%, with repos at both 0% and 100% `[measured on host]`. The
headline is a population number, not a typical-repo number.

## The three readings

They differ in what they assume the device can see. All `[measured on host]`.

**Strict per-file: 41.3%** (1,292 of 3,126). Every changed file is classified;
any unsupported file anywhere in the repo forces a fallback. This is the
shipped classifier applied to a full commit diff, as if the device saw
everything in the repository.

**Device watch scope: 72.4%** (2,262 of 3,126). The shipped watcher only sees
`<module>/src/**` plus the fixed gradle config files. Files outside that —
`README.md`, `.github/workflows/*.yml`, `fastlane/`, `.gitignore` — never reach
the classifier on a device at all, so they cannot invalidate anything. A
fallback commit **flips** to the fast path when *every* blocking file is
invisible to the watcher. Rules (`harness/watch_scope_report.py`):

- any gradle-config or manifest blocker: stays fallback (both are watched);
- any unsupported file *under `src/`*: stays fallback (watched, and genuinely
  unsupported — a `.png` misplaced in `src`, a native `.cpp`);
- otherwise it flips, and its route is whatever the visible remainder
  classifies as.

970 commits flip. Note what most of them are: **708 of the 970 flip to `NoOp`** —
commits whose only changed files are invisible, so on a device nothing happens
at all. The genuinely useful flips are 176 `CodeOnly`, 59 `CodeAndResources`,
25 `ResourcesOnly` and 2 `AssetsOnly`. Reading 72.4% as "72% of commits
hot-reload your change" is wrong. The fair split of all 3,126 commits: 49.5%
take a route that actually reloads something, 22.7% are device no-ops, and
27.6% still fall back.

**A "risky flip"** is a flip whose out-of-scope blockers are plausibly real build
inputs the watcher misses — `.pro`, `.properties`, `.toml`, `.cmake`, `.c`,
`.h`, `.gradle`, `.kts`, `.lock`, `.aar`, `.jar`, `.so` outside `src/`. On those,
the device would fast-path while a full build could legitimately produce
something different: the app keeps running with stale ProGuard rules or a stale
native library. There are **49 of them, 1.6% of all commits**, leaving
**70.8% sound**. That 1.6% is not a rounding error to be waved away — it is a
correctness exposure, and it is the honest reason the watch-scope number should
be quoted as "72.4%, of which 1.6 points are unsound".

**Multi-module: 60.6% of repos, 60.1% of commits.** 60 of 99 repos have more
than one module (module dirs = parents of a non-root `build.gradle[.kts]`, minus
`buildSrc` / `build-logic` / `gradle`), and 1,878 of 3,126 commits land in them.
Within those repos, under watch-scope semantics: 599 commits are code in one
module, 153 are code across multiple modules, 91 code plus resources in one
module, 52 code plus resources across modules, 41 resources only, 4 assets only;
463 stay fallback and 475 are invisible no-ops. Only 376 commits (20% of the
multi-module set) touch more than one module. So multi-module support matters
for the majority of real repos, but cross-module edits are a minority of the
edits inside them `[measured on host]`.

## What still cannot quick-build

Of the 864 commits (27.6% of all) that remain fallback under watch scope, causes
deduplicated per commit — a commit counts once per category, so the columns sum
to more than 100% `[measured on host]`:

| cause | commits | share of remaining fallback |
|---|---|---|
| build config (gradle files, version catalogs) | 710 | 82.2% |
| manifest | 146 | 16.9% |
| images and media under `src/` | 44 | 5.1% |
| non-resource XML | 38 | 4.4% |
| native code | 29 | 3.4% |
| codegen inputs | 19 | 2.2% |
| everything else (docs, scripts, CI, repo meta) | 46 | 5.3% |

Build config dominates by a wide margin, and the raw label counts say what most
of it is: `build.gradle.kts` 477, version catalog (`libs.versions.toml`) 206,
`gradle.properties` 81, `build.gradle` 71 — dependency and plugin-version bumps.
That is the single largest remaining gap, and it is also the one where a
fallback is arguably correct: a changed dependency really does need a real build.
The interesting sub-question is whether a *version-only* catalog bump could be
handled more cheaply than a full rebaseline, which nothing here answers.

For reference, the counterfactual the survey also computes: shipping a
docs/CI/repo-metadata ignore list would take the **strict** reading from 41.3%
to 59.1%. That ignore list is not shipped and does not need to be — the device
watcher already achieves the same effect and more, which is why the watch-scope
reading is the one that describes shipped behaviour.

## Making this a real metric

The survey is a static proxy and will stay one. Turning "what share of edits
hot-reload" into a measurement needs data from the device, not from GitHub. The
identified route is **encounter-rate analytics**: the session already emits
structured events per build, so counting routes taken per session — how often a
real user's save takes `CodeOnly` versus triggers a rebaseline, and in
multi-module projects how often an edit crosses a module boundary — would
answer the question directly, on the devices and project shapes we actually
ship to.

Until that exists, quote this survey for what it supports: the ranking of gaps
(build config first, manifest second, everything else a long tail), the fact
that most real repos are multi-module, and the fact that roughly 1.6% of commits
would take a fast path the shipped watcher cannot fully justify.
