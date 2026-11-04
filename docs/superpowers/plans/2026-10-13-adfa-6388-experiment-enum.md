[ADFA-6388](https://appdevforall.atlassian.net/browse/ADFA-6388)

# Plan: Experiment enum, tappable dot, nightly stale check

Branch `feature/ADFA-6388-experiment-enum`, worktree `.claude/worktrees/ADFA-6388`, base `origin/stage` @ 98e577ddf.

## Entries and keys (decision A, 2026-10-13)

Every originating ticket is Done, so each entry points at an open "Graduate or remove experiment" ticket instead (all linked to ADFA-6388):

| Entry | Gates | Key | Originally |
|---|---|---|---|
| `CRASH_ON_ICON_LONG_PRESS` | GreetingFragment.kt:37 | ADFA-6513 | ADFA-1219 |
| `RAISED_STORAGE_MINIMUM` | StorageUtils.kt:19 | ADFA-6514 | ADFA-3007 |
| `QUICK_BUILD` | EditorActivityActions.kt:95, ProjectHandlerActivity.kt:454, :895, QuickBuildGraphWarmUp.kt:69, QuickBuildBenchActivity.kt:54 (debug) | ADFA-6515 | ADFA-4128 |
| `ERROR_BAR_TOUCH_DISMISS` | FlashbarDismissGate.kt:30 | ADFA-6516 | ADFA-4128 |
| `PROFILER` | EditorActivityActions.kt:142, EditorBottomSheetTabAdapter.kt:128 | ADFA-6517 | ADFA-4333 |
| `GRADLE_AUTO_TUNE` | GradleBuildService.kt:564 | ADFA-6518 | ADFA-2784 |
| ~~`EXPERIMENTAL_ZIP_TEMPLATES`~~ | ZipTemplateReader.kt:56 | ~~ADFA-6519~~ | ADFA-2737 |

Zip templates dropped from the enum: filtering templates that mark themselves experimental is a permanent use of the switch, not a feature that graduates. ZipTemplateReader reads `Experiments.enabled`.

Line numbers corrected from the ticket: ProjectHandlerActivity 454/895 (not 452/893), GradleBuildService 564 (not 555).

## Commit 1 - refactor: add `Experiment` and route every gate through it

- `common/.../utils/Experiment.kt`:
  ```kotlin
  enum class Experiment(val label: String, val jiraKey: String) { ... }

  object Experiments {
  	/** Overall switch. Only for the title dot and IdeFeatureFlagService; gate features with [isOn]. */
  	val enabled: Boolean get() = FeatureFlags.isExperimentsEnabled
  	fun isOn(experiment: Experiment): Boolean = enabled
  }
  ```
  `isOn` ignores its argument today - all entries share one switch. The argument exists so deleting an entry breaks its gates at compile time.
- Migrate the 11 gates above to `Experiments.isOn(Experiment.X)`.
- `MainActivity.kt:203` and `IdeFeatureFlagServiceImpl.kt:8` (`:plugin-manager`, missing from the ticket) use `Experiments.enabled`.
- Delete unused `FeatureFlags` imports: rootPrefExts.kt:21, CloneRepositoryAction.kt:12, BaseAssetsInstaller.kt:5, LspHandler.kt:27.
- Plugin API unchanged (`plugin-api.api` untouched).

## Commit 2 - refactor: make `FeatureFlags.isExperimentsEnabled` internal

- `internal val isExperimentsEnabled` in `:common`. Kotlin-only callers, so `internal` holds (Java would still see a mangled public getter; no Java callers exist).
- `app/src/test/.../FeatureFlagsTest.kt` (Robolectric) reads the flag at 61-104: switch those reads to `Experiments.enabled`.
- `ExperimentsFlagHelper.kt` (androidTest): reflection resets `flags` but not the `loaded` latch, so `initialize()` likely no-ops and the assert fails. Confirm on device first, then replace the reflection with `runBlocking { FeatureFlags.refresh() }` and assert on `Experiments.isOn(Experiment.QUICK_BUILD)`. Helper takes an `Experiment` param so `QuickBuildFlagOffTest` names `Experiment.QUICK_BUILD`.
- One unit test in `:common` (`ExperimentsTest`): every entry follows the flag file on/off, and `jiraKey` matches `ADFA-\d+` (the nightly job greps for that).

## Commit 3 - feat: tap the title to list experiments

- When `Experiments.enabled`, `binding.codeOnTheGoLabel.setOnClickListener { showExperiments() }`. Whole toolbar, not just the dot: the Toolbar's title TextView has no `LinkMovementMethod`, so a `ClickableSpan` on "." won't fire. The existing long-click (tooltips, MainActivity.kt:414) is unaffected.
- Dialog: `DialogUtils.newMaterialDialogBuilder(this).setTitle(...).setItems(entries.map { "${it.label} (${it.jiraKey})" }, null)` - same pattern as NewFileAction.kt:537. List items wrap and scroll, so 2.0 font scale holds.
- New strings in `resources` (title only). Labels stay in the enum as plain English - developer-facing, behind a flag.
- Verify on emulator at font scale 1.0 and 2.0, flag on and off (no listener when off).

## Commit 4 - ci: nightly stale-experiment report

- `.github/workflows/stale-experiments.yml`, `schedule: cron`, plus `workflow_dispatch`. `permissions: { contents: read }`.
- Keys: `grep -oE 'ADFA-[0-9]+' common/.../Experiment.kt | sort -u`. No Gradle build needed.
- Per key: `curl -sS -f --max-time 30 -u "$AUTH" .../rest/api/3/issue/$KEY?fields=status`, auth `secrets.JIRA_EMAIL:secrets.JIRA_API_TOKEN` (pattern from `jira-advance-to-qa.yml`).
- Done -> `::warning::` + a line in `$GITHUB_STEP_SUMMARY`. Jira error -> warning. Always exit 0; not on `pull_request`.
- Can't run the secret-backed path locally (CI-only rule). Locally: run the script body with my own `JIRA_*` env to check the parsing; real run via `workflow_dispatch` after merge.

## Commit 5 - docs

- New `EXPERIMENTS.md` at the repo root - the one place for experiments: turning them on (`CodeOnTheGo.exp`, restart or `refresh()`), seeing what's on (tap the title), adding one (new entry + open graduation ticket, gate with `Experiments.isOn`), graduating (delete the entry, keep the "on" branch, delete the "off" branch), the nightly report, and the plugin API's single switch. Other docs link here instead of repeating it.
- ARCHITECTURE.md: one line pointing at EXPERIMENTS.md.
- CLAUDE.md (Project-specific constraints): new experimental code adds an `Experiment` entry, never reads the flag directly; see EXPERIMENTS.md.
- quickbuild/README.md:280, docs/manual-qa.md:20/307/310, docs/debugging.md:16-22: name `Experiment.QUICK_BUILD`, link EXPERIMENTS.md. Fix debugging.md's "never re-reads disk" (`refresh()` exists).
- docs/plugin-api.md:14 and PLUGIN_API_CHANGELOG.md:541: `IdeFeatureFlagService` is the overall switch, no per-experiment access; link EXPERIMENTS.md.

## Out of scope (per ticket)

Expiry dates; addons/`documentation.db` experimental markers; `WebServer.kt:137` own file check.

## Verification

- `flox activate -d flox/local -- ./gradlew :common:testV8DebugUnitTest :app:testV8DebugUnitTest --tests '*FeatureFlags*' --tests '*Experiments*'`
- Compile sweep: `:app`, `:templates-impl`, `:plugin-manager` `compileV8DebugKotlin`, plus `:app:compileV8DebugAndroidTestKotlin`.
- Prove `ExperimentsTest` fails with `isOn` stubbed to `false`.
- Grep: no `isExperimentsEnabled` outside `:common` and docs.
- Device: `QuickBuildFlagOffTest`; dot dialog at 1.0/2.0; final `:app:assembleV8Debug -x assetsDownloadDebug`.
