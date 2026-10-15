# ADFA-4930: Move Gradle build output off FUSE - implementation plan

2026-10-14. Branch `perf/ADFA-4930-fuse-storage`.

## Decision

Move each project's Gradle build output - every project's `build/` directory and the root `.gradle/` project cache - from the project folder on `/storage/emulated` (FUSE) to app-private storage. Sources stay where they are, visible to the user and over USB.

This is option 2 of the ticket's original analysis. Option 1, Quick Build's scratch tree, already moved (`QuickBuildScratch`, `EnvironmentQuickBuildPaths.kt:59`). This plan reuses its project key and storage location.

## Evidence

Measured 2026-10-14 with the IDE's Gradle arguments, same template project on both legs, ABBA order. Details and raw numbers are in the ticket comments.

| Device | Build | FUSE | App storage | Saving |
|---|---|---|---|---|
| Galaxy Note20 Ultra, Android 13 | warm edit (n=5) | 6.23 s | 4.07 s | 35% |
| Galaxy Note20 Ultra, Android 13 | warm clean | 17.2 s | 11.5 s | 33% |
| Pixel 6 Pro, Android 17 | warm edit (n=5) | 2.92 s | 2.22 s | 24% |
| Pixel 6 Pro, Android 17 | warm clean | 8.95 s | 6.37 s | 29% |
| Both | cold build after reboot, `--no-daemon` | | | none measurable |

- **Moving only the build output is enough.** Sources on FUSE with `build/` and `.gradle/` redirected to app storage recovered 91% of the FUSE cost for both edit and clean builds (Pixel, 6 and 3 rounds). No build output reached `/sdcard`.
- **FUSE costs waiting, not CPU.** Kernel CPU time is the same on both filesystems. strace puts the cost in file creation under `build/intermediates` (7.3 ms median on FUSE vs 56 us on f2fs).
- **Faster storage would add little.** Kernel time is 8-10% of build CPU, so making every remaining file operation instant would save at most 8-10% more. Android exposes no RAM filesystem to apps (`/dev/shm` does not exist; the tmpfs mounts belong to the system or the shell).
- **Not yet measured:** a large project, and the low tiers named in the original analysis (C107, itel A667L). See Validation.

## Design

### Where the output goes

`<noBackupFilesDir>/gradle-build/<projectKey>/`, next to Quick Build's `quickbuild-scratch/`. `noBackupFilesDir` is app-private f2fs and excluded from Auto Backup, which suits regenerable output.

- `<projectKey>` is `QuickBuildScratch.projectKey`: `<sanitized-basename>-<sha256-prefix>` of the normalized absolute project path. Extract it to a shared helper so both trees key a project the same way.
- Inside the tree:
  - `main/<project path>/` - each project's build directory in the main build (`main/_root/` for the root project, `main/app/` for `:app`).
  - `<name>-<hash>/<project path>/` - nested builds (`buildSrc`, `includeBuild`), keyed by their own root directory.
  - `project-cache/` - Gradle's `.gradle/`, via `--project-cache-dir`.
  - `kotlin/` - Kotlin's `.kotlin/`, via `kotlin.project.persistent.dir`. Confirm the property name and the minimum Kotlin Gradle plugin version before relying on it; skip it for older versions.
  - `project-path` - a one-line marker holding the project's absolute path, for the orphan sweep.

### How it is wired

1. **IDE side.** `GradleBuildService.getGradleExtraArgs` (`GradleBuildService.kt:697`) adds, when the feature is on:
   - `-Pandroidide.build.outputDir=<tree>`
   - `--project-cache-dir <tree>/project-cache`
   - `-Pkotlin.project.persistent.dir=<tree>/kotlin`

   Sync (`RootModelBuilder.kt:207`) and builds (`ToolingApiServerImpl.kt:313`) both take their arguments from `prepareBuild`, so this one change covers both. `prepareBuild` already reads the project path (`GradleBuildService.kt:552`).
2. **Gradle side.** `AndroidIDEInitScriptPlugin.apply` (`gradle-plugin`) reads the property from `gradle.startParameter.projectProperties` and, in `beforeProject`, sets `project.layout.buildDirectory`. This runs before any build script, so AGP, Kotlin and Quick Build's plugin all see the new location. The prototype init script did exactly this (`gradle.allprojects { p -> p.layout.buildDirectory.set(...) }`), with 33 builds and no output under the project.
3. **Flag.** A `BuildPreferences` switch, default off, until validation passes.

### What reads build output

| Where | What it does | Change |
|---|---|---|
| `QuickBuildTaskPaths.kt:44`, `GradleQuickBuildProvisioner.kt:463` | Reads `setup.json` from `<module>/build/quickbuild/<variant>/`, while `QuickBuildPlugin.kt:334` writes it under `layout.buildDirectory` | **Must change**: resolve from the module's build directory in the project model. Without this, Quick Build fails with "setup.json not found". |
| `BuildViewModel.kt:327` | Finds the plugin `.cgp` under `<projectRoot>/build/plugin` | **Must change**: use the root project's build directory from the model. `PluginBuilder.kt:207-217` already writes under `layout.buildDirectory`. |
| `AndroidModule.kt:191-243`, `JavaModule.kt:57-126` | Classpath, `R.jar` and dex files for the editor and run | None - built from `buildDirPath` in Gradle's model (`tooling-api-impl/.../GradleProjectExts.kt:20`). Verify. |
| `BuildViewModel.kt:164`, `:279` | Finds the APK | None - uses AGP's `assembleTaskOutputListingFile`. Verify. |
| `ApkInstaller.kt:203`, `ide_file_provider_paths.xml` | Installs the APK | None - streams into a PackageInstaller session; the FileProvider already covers `files-path` and `root-path`. |
| `QuickBuildPlugin.kt:205-334`, `PluginBuilder.kt:207-217` | Write Quick Build and plugin outputs | None - already relative to `layout.buildDirectory`. |
| `ProjectSearchOptions.kt:18`, `GetProjectDetails.kt:25`, `ProjectSyncHelper.kt:115`, `QuickBuildProjectLayout.kt:148`, `WatchFilter.kt:78` | Skip `build/` and `.gradle/` when walking a project | None. |

### Lifecycle

- **Project deleted in the IDE** (`RecentProjectsAdapter.kt:213`, `DeleteProjectFragment`): delete its output tree too.
- **Orphans** (project moved, renamed or deleted outside the IDE): at startup, off the main thread, remove trees whose `project-path` marker no longer exists - the same shape as `QuickBuildScratch.sweep`.
- **Existing `build/` folders in projects**: leave them. They go stale but do no harm. A later "free space" action can offer to remove them.
- **Storage**: output now counts as app data in Settings, and "Clear storage" removes it (the next build is a full one). Uninstalling removes it as well; sources are unaffected.

### What users will notice

These are the tradeoffs from the original analysis, with how this plan handles each:

- **The APK is no longer in the project folder**, file manager or MTP. Add an **Export APK** action that copies the built APK to `Download/` or opens the share sheet. The survey found no existing export or share action.
- **Build output no longer travels with a copied project folder.** That's acceptable: it can be rebuilt, and copying `build/` is rarely wanted.
- **Terminal builds** (`./gradlew` in the IDE's terminal) don't get the redirect, so they still build into the project folder, separately from the IDE's tree. Document this. Changing it is a follow-up if users trip over it.

## PR stack

Each PR compiles and passes tests on its own; the flag stays off until PR 4.

1. **Mechanism, behind the flag.** Shared project-key helper extracted from `QuickBuildScratch`; output-tree resolver; the extra arguments; the init-script plugin redirect. Tests: key and argument unit tests, and a `:gradle-plugin` TestKit test that the build directory and project cache land under the given tree. About 6 files.
2. **Consumers.** Quick Build `setup.json` lookup and plugin `.cgp` lookup from the model. Tests for both. About 4 files.
3. **Lifecycle and UX.** Delete-with-project, the orphan sweep, Export APK, strings. About 6 files.
4. **Default on.** Flip the flag after validation. Update `ARCHITECTURE.md` (storage layout) and any user docs.

## Validation before default-on

- Re-run the three-way benchmark (`sdcard` / `split` / `home`) on a large project such as `sora-editor-full`, and on one low-tier phone (C107 or itel A667L).
- Full regression on a device: build and run, Quick Build, a plugin project, clean, sync, a project with `buildSrc`, a Kotlin project.

## Steps to QA (draft)

```gherkin
Feature: Build output in app storage

  Background:
    Given build output relocation is enabled in Build preferences

  Scenario: A project builds and runs with output in app storage
    Given a new Empty Activity project in CodeOnTheGoProjects
    When I build and run it
    Then the app installs and launches
    And the project folder contains no "build" or ".gradle" folder

  Scenario: Quick Build still works
    Given an open project that has been built once
    When I edit MainActivity and run Quick Build
    Then the change appears in the running app

  Scenario: A plugin project still produces its plugin
    Given a new Plugin project
    When I build it
    Then the IDE offers to install the built plugin

  Scenario: Export APK
    Given a built project
    When I choose Export APK
    Then the APK appears in Download

  Scenario: Deleting a project frees its build output
    Given a built project
    When I delete it from the main screen
    Then the app's storage in Android Settings drops by about the size of its build output

  Scenario: An old project with a build folder
    Given a project that already has a "build" folder from an earlier version
    When I build it
    Then the build succeeds
    And the old "build" folder is left unchanged
```

## Out of scope

- Gradle file-system watching on Android - ADFA-6545.
- The IDE's own per-project cache, `<project>/.cg/`. It stays on FUSE: few files, written on sync only.
- Samsung Freecess freezing a backgrounded app also stalled Gradle's localhost client-daemon connection in the benchmark (seen with `run-as` builds; whether IDE builds are affected is unverified).
