---
name: architecture-review
description: Review a code change against Code On The Go's architecture — ARCHITECTURE.md and the ADRs in docs/adr/. Forces a read of those authoritative docs (they are NOT reliably in context otherwise), then checks the diff against UDF/state, Koin DI, Room-vs-SQLite, Compose, module boundaries, ABI flavors, dependency substitution, @Parcelize, and strings placement, reporting violations with the ADR/section each comes from. Use when asked to review architecture alignment, check a change against ARCHITECTURE.md/the ADRs, or as the §10 step of a code review.
metadata:
  author: Hal Eisen
  keywords:
  - architecture
  - review
  - adr
  - udf
  - code-review
  - codeonthego
---

## Why this is a skill (not just REVIEW.md §10)

ARCHITECTURE.md (~4.9k tokens) and the ADRs (~7.3k) are **not** in context during normal work, and prose links in REVIEW.md are not reliably followed. This skill exists to **guarantee the read**: it opens the authoritative docs, then checks the diff against them. Do not review architecture from memory or from the summary in this file alone.

## When to invoke

- "Review this against the architecture / ARCHITECTURE.md / the ADRs."
- "Does this change follow our patterns?" / architecture-alignment review.
- As the §10 step of a full code review — its output feeds REVIEW.md's evidence ledger.

## Step 1 — Scope the diff

Pick the target and get the changed files + hunks:
- Working tree: `git diff --stat` and `git diff`.
- This branch vs the integration branch: `git diff origin/stage...HEAD` (feature branches are based on `stage`).
- A GitHub PR: `gh pr diff <N>`.

**Exclude vendored/generated code** — it's not held to our patterns: `composite-builds/build-deps*`, `subprojects/{aaptcompiler,builder-model-impl,flashbar,xml-dom}`, `termux/`, `eventbus/`, `LayoutEditor/`, `**/build/`, generated `R`/`BuildConfig`. Review only first-party Kotlin/Java/XML/Gradle changes.

## Step 2 — READ the authoritative docs (mandatory)

Before judging anything, read:
- `ARCHITECTURE.md` (whole file — module map, dependency rules, tech stack, **State Management**, testing).
- **Every** file in `docs/adr/*.md`. At minimum open the ones a diff can plausibly violate: 0001 (Room), 0003 (substitution), 0005 (flavors), 0006 (Koin), 0009 (Compose). Read the rest if the change is broad.

Do not skip this because the rules "look familiar" — they are the source of truth and they change.

## Step 3 — Check the diff against the rules

For each changed first-party file, check the applicable rules. Each rule cites its source so findings are traceable.

| # | Rule | Source |
|---|---|---|
| 1 | **UDF:** new screens use `ViewModel` + `StateFlow<UiState>`, sealed `UiEvent`/`UiEffect`, a repository for data; composables collect via `collectAsStateWithLifecycle()` (lifecycle-aware, Android's strongly-recommended default; `collectAsState()` is only for platform-agnostic/KMP code, which we don't have); no I/O or business logic in composables/Activities/Fragments. | ARCHITECTURE.md → State Management |
| 2 | **Sealed state for mutually-exclusive states** (loading/content/error/…): not a `data class` of independent `Boolean`s that can contradict each other ("boolean hell"). | ARCHITECTURE.md → State Management |
| 3 | **Koin DI**, constructor injection; register new singletons/ViewModels in the module. No hand-rolled singletons/service locators (the documented `ServiceLocator` aside). | ADR 0006 |
| 4 | **Persistence:** Room is the default for relational data; raw SQLite only for a justified exception (prebuilt read-only DB, perf/allocation-critical indexing, cross-boundary schema) — and the PR must say which. Non-relational → filesystem/preferences (DataStore). | ADR 0001 |
| 5 | **`@Parcelize`** (`kotlin-parcelize`) for `Parcelable`; never hand-implement it unless Parcelize genuinely can't. | ARCHITECTURE.md → Parceling |
| 6 | **New UI is Jetpack Compose** — a new XML-layout / `Fragment`-rendered screen for the IDE's own UI is a violation (existing XML screens are fine until reworked). | ADR 0009 |
| 7 | **Module boundaries / dependency direction:** UI → ViewModel → Repository → data source; features depend on `common`/`utils`, not the reverse; no new cross-feature or upward dependency. | ARCHITECTURE.md → module map |
| 8 | **ABI flavors:** new Android modules get `v7`/`v8` via `composite-builds/build-logic` centrally — no per-module flavor blocks, no flavorless `assembleDebug`. (`:plugin-api` is intentionally flavorless.) | ADR 0005 |
| 9 | **Dependency substitution:** don't add a Maven coordinate for something already vendored/substituted (`build-deps*`); don't add a new dependency without checking `gradle/libs.versions.toml` first. | ADR 0003 |
| 10 | **Strings** live in the `:resources` module's `strings.xml` (not per-module, not inline literals). | REVIEW.md §7 |
| 11 | **UI never drawn over the two system bars** (top status bar, bottom navigation bar). | CLAUDE.md |

Rules 1, 2, 6 apply to UI changes; 4, 5 to data/model changes; 8, 9 to Gradle changes. Judge by what the diff touches — don't flag rules a file doesn't engage.

For a **large diff (~15+ first-party files)**, fan out: spawn a subagent per dimension (UI/state, DI, persistence, Gradle/modules), each instructed to read the relevant ADR and report only its dimension's findings; then merge. For a small diff, do it inline.

## Step 4 — Report

Output a findings table, most-severe first. Every finding must cite the rule source and give a concrete fix. If a rule was checked and passes, say so (the evidence ledger wants pass/fail, not silence).

```
### Architecture review — <scope>
Docs read: ARCHITECTURE.md, docs/adr/0001,0003,0005,0006,0009 (+others as needed)

| Verdict | File:line | Rule | Source | Fix |
|---|---|---|---|---|
| ❌ | ui/FooScreen.kt:42 | Mutually-exclusive state as booleans | ADR 0001 sibling / State Mgmt | Model as a sealed FooUiState (Loading/Content/Error) |
| ⚠️ | data/BarStore.kt:10 | Raw SQLite without stated justification | ADR 0001 | Use Room, or state which exception applies in the PR |
| ✅ | — | Koin DI | ADR 0006 | new VM registered in module, constructor-injected |

Summary: <n> violations, <n> warnings, <n> checks passed.
```

- **❌ violation** = contradicts a rule; blocking. **⚠️ warning** = likely issue / missing justification. **✅** = checked and clean.
- If nothing architectural changed (e.g. a docs- or test-only diff), say so explicitly rather than inventing findings.

## Notes

- This is a *conformance* check against our documented patterns — not a general bug hunt (use `/code-review` for correctness). Keep findings tied to a specific ADR/section.
- If a rule seems wrong or outdated for the change at hand, flag it as a possible ADR update rather than forcing the code to fit — the ADRs are `Proposed`, not immutable.
