# Inject plugin-api + builder coordinates into the on-device localMvnRepository

**Ticket:** ADFA-4911 (blocks ADFA-4693; relates to ADFA-4908, ADFA-4913)
**Branch:** `ADFA-4911-inject-plugin-jars-localmvn`
**Date:** 2026-07-28

## Goal

During Code On The Go onboarding, materialize the plugin build artifacts into the
freshly-unpacked on-device local Maven repository (`Environment.LOCAL_MAVEN_DIR` =
`$HOME/maven/localMvnRepository`) as proper Maven coordinates (jars + POMs + the Gradle
plugin marker). Plugin projects then resolve the plugin API and the
`com.itsaky.androidide.plugins.build` plugin **by coordinate** — offline, on-device, with
**no** per-plugin `libs/*.jar` files.

This unblocks building plugins inside CoGo at scale without committing per-plugin jar
copies, and is a prerequisite for ADFA-4693.

## Why the plumbing already works (verified against `stage`)

- `COTGSettingsPlugin` injects the local repo into **both** resolution scopes
  (`gradle-plugin/src/main/java/com/itsaky/androidide/gradle/COTGSettingsPlugin.kt:46-48`):
  ```kotlin
  dependencyResolutionManagement.repositories.addLocalRepos(allLocalRepos) // compileOnly coordinates
  pluginManagement.repositories.addLocalRepos(allLocalRepos)               // plugins { id() } markers
  ```
  The always-present repo is `MAVEN_LOCAL_REPOSITORY` (`org.adfa.constants`,
  `constants.kt:64`) = `/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository`,
  the exact directory `Environment.LOCAL_MAVEN_DIR` resolves to on-device
  (`common/src/main/java/com/itsaky/androidide/utils/Environment.java:187`).
- `plugin-builder` is already a real Gradle plugin — `group=com.itsaky.androidide.plugins`,
  `version=1.0.0`, id `com.itsaky.androidide.plugins.build`
  (`plugin-api/plugin-builder/build.gradle.kts:5-19`). It is simply never published.
- Onboarding already unpacks the flat jars: both installers extract
  `plugin-artifacts.zip` into `Environment.PLUGIN_API_JAR.parentFile` (= `.cg/plugin-api/`).

## Why at onboarding (not baked into localMvnRepository.zip)

`localMvnRepository.zip` is a *harvested* asset — scraped from the Gradle cache after CoGo
builds its templates online. The plugin-api/builder jars are never downloaded during those
builds, so a harvest would never include them. Injecting at onboarding reuses the
already-shipped plugin build outputs as the single source of truth, keeps versions in sync,
and leaves the fragile harvest pipeline untouched.

## Verified wrinkles that shaped the design

1. **`plugin-api` is a curated API module** — `com.android.library` with a
   binary-compatibility validator (`plugin-api/build.gradle.kts:31-34`) and
   `@InternalPluginApi` markers. Its jar is *scraped* from
   `intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar` by the
   `createPluginApiJar` Copy task (L48-53); `1.0.0` exists only as a rename string. It has
   **no** group/version/publishing. Physically merging other modules *into* this module
   would pollute its locked API surface — so we merge at the **packaging** layer instead.
2. **`common`, `eventbus-events`, `idetooltips`** are `com.android.library` modules. Unlike
   plugin-api, `configureAndroidModule` (`AndroidModuleConf.kt:232-249`, applied to every
   Android library **except** `:plugin-api`) injects `v7`/`v8` product flavors, so their
   compiled classes land at flavor-qualified paths
   (`intermediates/aar_main_jar/v8Release/syncV8ReleaseLibJars/classes.jar`, task
   `assembleV8Release`). The fat jar harvests the **v8** flavor for these three — their
   classes are ABI-neutral API (the only flavor difference is `BuildConfig.ABI_*` strings,
   which plugins never compile against), so one v8 harvest is safe for the single shared
   asset. plugin-api stays flavorless (`.../release/syncReleaseLibJars/classes.jar`). None
   of the three has group/version/publishing.
3. **Neither `plugin-api` nor `plugin-builder` applies `maven-publish`.** `plugin-builder`
   is a separate **included build**, consumed via `gradle.includedBuild("plugin-builder")`.
4. **POM transitives cut both ways:** the builder's POM *must* carry its config-time deps
   (Gradle runs the plugin); the compile-only API jar should have a **dependency-free** POM
   so offline resolution does not chase transitives absent from `localMvnRepository`. This
   matches today's reality — plugins already compile against flat jars with no transitives.
5. In-repo example plugins use `compileOnly(project(":plugin-api"))`, not `libs/*.jar`; the
   five-flat-jars pattern lives in the external plugin-examples repo (ADFA-4908's concern).

## Chosen approach: fat compile-jar + published builder, shipped as a sibling asset

### Coordinates shipped (3)

| Coordinate | Contents | POM |
|---|---|---|
| `com.itsaky.androidide:plugin-api:1.0.0` | **Fat jar** = merged `classes.jar` of plugin-api + common + eventbus-events + idetooltips | dependency-free (`packaging=jar`) |
| `com.itsaky.androidide.plugins:plugin-builder:1.0.0` | builder impl jar | real config-time deps (maven-publish generated) |
| `com.itsaky.androidide.plugins.build:com.itsaky.androidide.plugins.build.gradle.plugin:1.0.0` | Gradle plugin **marker** | depends on the impl (auto-generated) |

**Decisions:** coordinate name stays `plugin-api` (satisfies the AC line verbatim; it is
what plugins already expect). Version `1.0.0` across the board. Gradle Module Metadata
(`.module`) disabled on the builder so only jars + POMs ship (parity with the harvested
repo; marker/plugin resolution works off POMs alone). The 3 add-on classes are harvested
from the existing `aar_main_jar` intermediate — **no** new files in those modules.

### Build-time assembly (host CoGo build)

All new logic lives in `app/build.gradle.kts`, plus one publishing block in
`plugin-api/plugin-builder/build.gradle.kts`.

1. **`assemblePluginApiFatJar`** (new `Jar` task): `dependsOn` `:plugin-api:assembleRelease`
   + `:common:assembleV8Release`, `:eventbus-events:assembleV8Release`,
   `:idetooltips:assembleV8Release`; merges each module's `classes.jar` via
   `from(zipTree(...))` (plugin-api at `.../release/syncReleaseLibJars/`, the other three at
   `.../v8Release/syncV8ReleaseLibJars/`); `duplicatesStrategy = EXCLUDE`. Produces
   `plugin-api-1.0.0.jar` (fat).
   - Per-module `.kotlin_module` files carry distinct names; `R`/`BuildConfig` live in
     distinct package namespaces — no collisions across first-party modules.
2. **Static POM** for plugin-api: a dependency-free `packaging=jar` POM written into the
   Maven layout. Hand-writing this is safe (the no-transitives hazard applies only to the
   builder).
3. **`plugin-builder/build.gradle.kts`**: add `` `maven-publish` `` and a
   `maven { url = layout.buildDirectory.dir("plugin-maven-repo") }` publishing repo.
   `java-gradle-plugin` (auto-applied by `kotlin-dsl`) emits the impl publication **and**
   the marker. Disable `GenerateModuleMetadata`. App wires a `dependsOn` on the builder's
   `publish…ToPluginRepoRepository` task via `gradle.includedBuild("plugin-builder")`.
4. **`createPluginMavenRepoZip`** (new `Zip`): merges the fat jar + static POM + the
   builder's published tree into a single `com/itsaky/androidide/…` Maven layout →
   `assets/plugin-maven-repo.zip`. Wire the new asset into the per-arch bundling
   (`createAssetsZip(arch)`) and the common-asset list (`AndroidIDEAssetsPlugin.kt`).

### On-device merge (onboarding)

**Verified hazard:** the generic archive branch **wipes** its destination first
(`destDir.deleteRecursively()`, BundledAssetsInstaller.kt:61-63 / SplitAssetsInstaller.kt:
68-70), and all entries install **concurrently** (`async` + `joinAll`,
AssetsInstallationHelper.kt:160-174). A naive second entry targeting `LOCAL_MAVEN_DIR`
would race the `localMvnRepository.zip` wipe and be destroyed.

**Chosen mechanism — apply the overlay inside the existing localMvnRepository branch:**

- Add a `PLUGIN_MAVEN_REPO_ZIP = "plugin-maven-repo.zip"` asset constant (+ the `.br`
  variant name), and ship the asset (bundled common `.br` + inside the split
  `assets-<arch>.zip`). **Do NOT** add it to `expectedEntries` — it must not be a separate
  concurrent job.
- In **both** installers, give `LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME` its own branch that:
  (1) wipes + extracts the harvested `localMvnRepository.zip` into `LOCAL_MAVEN_DIR` as
  today, then (2) in the **same** job, reads `plugin-maven-repo.zip` and extracts it into
  the same dir via `AssetsInstallationHelper.extractZipToDir` (which creates dirs and
  copies **without** wiping — a true merge). Bundled reads the `.br` common asset through
  `BrotliInputStream`; split reads the `plugin-maven-repo.zip` entry from the already-open
  `zipFile`. Because the overlay runs sequentially after the wipe within the one
  localMvnRepository job, there is no race and no separate entry.
- The existing `plugin-artifacts.zip → .cg/plugin-api/` branch is untouched (still feeds
  `isPluginProject`'s `libs/plugin-api.jar` check until ADFA-4913).

## Out of scope (separate tickets)

- Changing plugin detection from the `libs/plugin-api.jar` file check to the manifest
  `plugin.id` meta-data (`ProjectValidations.isPluginProject`) — ADFA-4913.
- Migrating the external plugin-examples plugins to coordinate-based references + a
  committed CI `maven-repo/` — ADFA-4908.

## Testing & verification

- **Installer unit test:** extract a fixture `plugin-maven-repo.zip` into a temp
  `LOCAL_MAVEN_DIR`; assert the 3 coordinate paths land, a pre-existing harvested-style
  entry survives the merge, and a `../evil` traversal entry is rejected.
- **Build test:** unzip the produced `plugin-maven-repo.zip`; assert the exact
  coordinate / POM / marker paths exist and the fat jar contains a class from each of the
  4 source modules.
- **On-device (manual/instrumented):** a minimal plugin with **no** `libs/` directory,
  using `plugins { id("com.itsaky.androidide.plugins.build") version "1.0.0" }` +
  `compileOnly("com.itsaky.androidide:plugin-api:1.0.0")`, builds `:assemblePluginDebug`
  fully offline → produces a `.cgp`.

## Acceptance criteria (from ticket)

- On a fresh CoGo install, `localMvnRepository` contains plugin-api, plugin-builder, and
  the `com.itsaky.androidide.plugins.build` plugin marker at the correct coordinates
  (verify by `find` on device).
- A plugin project with **no** `libs/` directory, using the builder plugin + `compileOnly`
  plugin-api, builds `:assemblePluginDebug` successfully on-device, fully offline,
  producing a `.cgp`.
- The plugin-api coordinate version (`1.0.0`) is documented and matches the shipped jar.

## Files touched (net)

`plugin-api/plugin-builder/build.gradle.kts`, `app/build.gradle.kts`, the asset-name
constants (`org/adfa/constants/constants.kt`), the common-asset brotli registration
(`AndroidIDEAssetsPlugin`), `BundledAssetsInstaller.kt`, `SplitAssetsInstaller.kt`, one
docs file. **Untouched:** plugin-api, common, eventbus-events, idetooltips modules.
