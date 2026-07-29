# Inject plugin-api + builder coordinates into localMvnRepository — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** During CoGo onboarding, materialize plugin-api (fat compile jar), plugin-builder, and the `com.itsaky.androidide.plugins.build` marker into the on-device `localMvnRepository` as real Maven coordinates, so plugins resolve them by coordinate, offline, with no `libs/*.jar`.

**Architecture:** Build-time, the host CoGo build assembles a small Maven-layout zip (`plugin-maven-repo.zip`): a fat `com.itsaky.androidide:plugin-api:1.0.0` jar (merged classes of plugin-api + common + eventbus-events + idetooltips, dependency-free POM) plus the builder impl + POM + marker emitted by real `maven-publish`. On-device, the two installers extract that zip into `LOCAL_MAVEN_DIR` **inside** the existing `localMvnRepository` branch (after its wipe+extract, via the non-wiping `extractZipToDir`) to avoid a wipe/concurrency race.

**Tech Stack:** Gradle Kotlin DSL, `maven-publish` + `java-gradle-plugin`, AGP `com.android.library`, Kotlin, brotli4j, java.nio zip. Build wrapped in `flox activate -d flox/local -- ./gradlew`.

## Global Constraints

- **Build wrapper:** every Gradle call is `flox activate -d flox/local -- ./gradlew <task>`.
- **Worktree:** work in `~/src/cogo/ADFA-4911` (branch `ADFA-4911-inject-plugin-jars-localmvn`); `app/google-services.json` already copied in.
- **Coordinates:** `com.itsaky.androidide:plugin-api:1.0.0` (jar), `com.itsaky.androidide.plugins:plugin-builder:1.0.0`, marker `com.itsaky.androidide.plugins.build:com.itsaky.androidide.plugins.build.gradle.plugin:1.0.0`. Version `1.0.0` everywhere.
- **Do NOT** add `plugin-maven-repo.zip` to `AssetsInstallationHelper.expectedEntries` — it must not become a concurrent install job (would race the `LOCAL_MAVEN_DIR` wipe). It is applied inside the `localMvnRepository` branch only.
- **Do NOT** touch the `plugin-artifacts.zip → .cg/plugin-api/` flow (still feeds `isPluginProject` until ADFA-4913), the harvest pipeline, or the plugin-api / common / eventbus-events / idetooltips module build files.
- **Fat-jar harvest paths:** plugin-api `intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar`; the other three (v7/v8 flavored) `intermediates/aar_main_jar/v8Release/syncV8ReleaseLibJars/classes.jar`.
- **Code style:** tabs, LF; run `spotlessApply` before any commit that touches Kotlin/gradle.kts. Branch name already matches `ADFA-#####`.
- **Links:** the Maven POM `xmlns="http://maven.apache.org/POM/4.0.0"` is a standard XML **namespace identifier**, never dereferenced (no network) — it is required for a well-formed POM and is the one allowed http string.

---

### Task 1: Publish plugin-builder to a build-dir Maven repo (impl POM + marker)

**Files:**
- Modify: `plugin-api/plugin-builder/build.gradle.kts`

**Interfaces:**
- Produces: a Maven layout under `plugin-api/plugin-builder/build/plugin-maven-repo/` containing
  `com/itsaky/androidide/plugins/plugin-builder/1.0.0/plugin-builder-1.0.0.{jar,pom}` and
  `com/itsaky/androidide/plugins/build/com.itsaky.androidide.plugins.build.gradle.plugin/1.0.0/*.pom`.
- Produces: publish task `publishAllPublicationsToPluginMavenRepoRepository` (referenced by Task 3).

- [ ] **Step 1: Add `maven-publish`, a build-dir repo, and disable module metadata**

Edit `plugin-api/plugin-builder/build.gradle.kts`:

```kotlin
plugins {
	`kotlin-dsl`
	`maven-publish`
}

group = "com.itsaky.androidide.plugins"
version = "1.0.0"

dependencies {
	implementation("com.android.tools.build:gradle:8.8.2")
}

gradlePlugin {
	plugins {
		create("pluginBuilder") {
			id = "com.itsaky.androidide.plugins.build"
			implementationClass = "com.itsaky.androidide.plugins.build.PluginBuilder"
			displayName = "Code on the Go Plugin Builder"
			description = "Gradle plugin for building Code on the Go plugins"
		}
	}
}

publishing {
	repositories {
		maven {
			name = "pluginMavenRepo"
			url = uri(layout.buildDirectory.dir("plugin-maven-repo"))
		}
	}
}

// Ship POMs only (parity with the harvested repo); marker/plugin resolution works off POMs.
tasks.withType<GenerateModuleMetadata>().configureEach { enabled = false }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
	compilerOptions {
		apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
		languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
	}
}
```

`java-gradle-plugin` (auto-applied by `kotlin-dsl`) auto-creates the `pluginMaven` (impl) and `pluginBuilderPluginMarkerMaven` (marker) publications; `maven-publish` adds the `publishAllPublicationsToPluginMavenRepoRepository` task.

- [ ] **Step 2: Run the publish task and confirm the exact task name**

Run: `flox activate -d flox/local -- ./gradlew -p plugin-api/plugin-builder tasks --all | grep -i publish`
Expected: a line `publishAllPublicationsToPluginMavenRepoRepository`. If the name differs, use the actual name in Task 3.

- [ ] **Step 3: Publish and inspect the output layout**

Run:
```bash
flox activate -d flox/local -- ./gradlew -p plugin-api/plugin-builder publishAllPublicationsToPluginMavenRepoRepository
find plugin-api/plugin-builder/build/plugin-maven-repo -type f | sort
```
Expected files (no `.module`):
```
.../com/itsaky/androidide/plugins/build/com.itsaky.androidide.plugins.build.gradle.plugin/1.0.0/com.itsaky.androidide.plugins.build.gradle.plugin-1.0.0.pom
.../com/itsaky/androidide/plugins/plugin-builder/1.0.0/plugin-builder-1.0.0.jar
.../com/itsaky/androidide/plugins/plugin-builder/1.0.0/plugin-builder-1.0.0.pom
```

- [ ] **Step 4: Verify the POMs carry the right dependencies**

Run: `grep -A3 -i "artifactId" plugin-api/plugin-builder/build/plugin-maven-repo/com/itsaky/androidide/plugins/plugin-builder/1.0.0/plugin-builder-1.0.0.pom`
Expected: the impl POM lists `com.android.tools.build:gradle` (config-time dep). The marker POM depends on `com.itsaky.androidide.plugins:plugin-builder:1.0.0`:
Run: `grep -i "plugin-builder" plugin-api/plugin-builder/build/plugin-maven-repo/com/itsaky/androidide/plugins/build/*/1.0.0/*.pom`
Expected: a `<dependency>` on `plugin-builder` `1.0.0`.

- [ ] **Step 5: Commit**

```bash
cd ~/src/cogo/ADFA-4911
flox activate -d flox/local -- ./gradlew spotlessApply
git add plugin-api/plugin-builder/build.gradle.kts
git commit -m "ADFA-4911: Publish plugin-builder (impl POM + Gradle plugin marker) to a build-dir maven repo"
```

---

### Task 2: Assemble the fat plugin-api jar

**Files:**
- Modify: `app/build.gradle.kts` (add task near the existing `createPluginArtifactsZip`, ~L435)

**Interfaces:**
- Produces: `app/build/plugin-maven-repo-staging/plugin-api-1.0.0.jar` — a jar containing the merged main classes of `:plugin-api`, `:common`, `:eventbus-events`, `:idetooltips`.

- [ ] **Step 1: Register the fat-jar task**

Add to `app/build.gradle.kts` (after `createPluginArtifactsZip`, before `createAssetsZip`):

```kotlin
// Fat compile-only jar published as com.itsaky.androidide:plugin-api:1.0.0.
// Merges the API surface plugins already compile against (plugin-api + common +
// eventbus-events + idetooltips) into one coordinate. The three add-ons are
// v7/v8-flavored (unlike plugin-api); their classes are ABI-neutral so v8 is used.
tasks.register<Jar>("assemblePluginApiFatJar") {
	dependsOn(
		":plugin-api:assembleRelease",
		":common:assembleV8Release",
		":eventbus-events:assembleV8Release",
		":idetooltips:assembleV8Release",
	)
	archiveFileName.set("plugin-api-1.0.0.jar")
	destinationDirectory.set(layout.buildDirectory.dir("plugin-maven-repo-staging"))
	duplicatesStrategy = DuplicatesStrategy.EXCLUDE

	from(zipTree(project(":plugin-api").layout.buildDirectory
		.file("intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar").get().asFile))
	from(zipTree(project(":common").layout.buildDirectory
		.file("intermediates/aar_main_jar/v8Release/syncV8ReleaseLibJars/classes.jar").get().asFile))
	from(zipTree(project(":eventbus-events").layout.buildDirectory
		.file("intermediates/aar_main_jar/v8Release/syncV8ReleaseLibJars/classes.jar").get().asFile))
	from(zipTree(project(":idetooltips").layout.buildDirectory
		.file("intermediates/aar_main_jar/v8Release/syncV8ReleaseLibJars/classes.jar").get().asFile))
}
```

- [ ] **Step 2: Build the fat jar**

Run: `flox activate -d flox/local -- ./gradlew :app:assemblePluginApiFatJar`
Expected: BUILD SUCCESSFUL; `app/build/plugin-maven-repo-staging/plugin-api-1.0.0.jar` exists. If a `classes.jar` path is wrong, the build fails on a missing zip input — fix the path (verify with `find <module>/build/intermediates/aar_main_jar -name classes.jar`).

- [ ] **Step 3: Verify the jar contains a class from each of the 4 modules**

Run:
```bash
unzip -l app/build/plugin-maven-repo-staging/plugin-api-1.0.0.jar | \
  grep -E "com/itsaky/androidide/(plugins/api|common|eventbus|idetooltips)" | head
```
Expected: at least one `.class` under each of the four package roots (`plugins/api`, `common`, `eventbus`, `idetooltips`). If any is missing, that module's `classes.jar` path is wrong.

- [ ] **Step 4: Commit**

```bash
cd ~/src/cogo/ADFA-4911
flox activate -d flox/local -- ./gradlew spotlessApply
git add app/build.gradle.kts
git commit -m "ADFA-4911: Assemble fat plugin-api jar (plugin-api + common + eventbus-events + idetooltips)"
```

---

### Task 3: Write the plugin-api POM and assemble `plugin-maven-repo.zip`

**Files:**
- Modify: `app/build.gradle.kts` (add `writePluginApiPom` + `createPluginMavenRepoZip` after Task 2's task)

**Interfaces:**
- Consumes: Task 1's `publishAllPublicationsToPluginMavenRepoRepository`; Task 2's `assemblePluginApiFatJar`.
- Produces: `assets/plugin-maven-repo.zip` — a Maven layout with all three coordinates.

- [ ] **Step 1: Register the POM writer and the zip assembler**

Add to `app/build.gradle.kts` (after `assemblePluginApiFatJar`):

```kotlin
// Dependency-free POM for the fat plugin-api coordinate: it is compile-only/provided,
// so it must NOT drag transitives that would need offline resolution.
tasks.register("writePluginApiPom") {
	val pomFile = layout.buildDirectory.file("plugin-maven-repo-staging/plugin-api-1.0.0.pom")
	outputs.file(pomFile)
	doLast {
		pomFile.get().asFile.writeText(
			"""<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<groupId>com.itsaky.androidide</groupId>
	<artifactId>plugin-api</artifactId>
	<version>1.0.0</version>
	<packaging>jar</packaging>
</project>
""",
		)
	}
}

// Assembles the shippable Maven layout: the fat plugin-api coordinate + the
// builder impl/POM/marker published by the plugin-builder included build.
tasks.register<Zip>("createPluginMavenRepoZip") {
	dependsOn("assemblePluginApiFatJar", "writePluginApiPom")
	dependsOn(gradle.includedBuild("plugin-builder")
		.task(":publishAllPublicationsToPluginMavenRepoRepository"))

	archiveFileName.set("plugin-maven-repo.zip")
	destinationDirectory.set(rootProject.file("assets"))

	into("com/itsaky/androidide/plugin-api/1.0.0") {
		from(layout.buildDirectory.file("plugin-maven-repo-staging/plugin-api-1.0.0.jar"))
		from(layout.buildDirectory.file("plugin-maven-repo-staging/plugin-api-1.0.0.pom"))
	}
	// Builder tree is already in Maven layout (com/itsaky/androidide/plugins/...).
	from(rootProject.file("plugin-api/plugin-builder/build/plugin-maven-repo"))
}
```

- [ ] **Step 2: Build the zip**

Run: `flox activate -d flox/local -- ./gradlew :app:createPluginMavenRepoZip`
Expected: BUILD SUCCESSFUL; `assets/plugin-maven-repo.zip` exists.

- [ ] **Step 3: Verify the coordinate layout inside the zip**

Run: `unzip -l assets/plugin-maven-repo.zip | grep -E "1.0.0/" | sort`
Expected exactly these artifact paths (order aside):
```
com/itsaky/androidide/plugin-api/1.0.0/plugin-api-1.0.0.jar
com/itsaky/androidide/plugin-api/1.0.0/plugin-api-1.0.0.pom
com/itsaky/androidide/plugins/plugin-builder/1.0.0/plugin-builder-1.0.0.jar
com/itsaky/androidide/plugins/plugin-builder/1.0.0/plugin-builder-1.0.0.pom
com/itsaky/androidide/plugins/build/com.itsaky.androidide.plugins.build.gradle.plugin/1.0.0/com.itsaky.androidide.plugins.build.gradle.plugin-1.0.0.pom
```

- [ ] **Step 4: Commit**

```bash
cd ~/src/cogo/ADFA-4911
flox activate -d flox/local -- ./gradlew spotlessApply
git add app/build.gradle.kts
git commit -m "ADFA-4911: Assemble plugin-maven-repo.zip (plugin-api coordinate + builder + marker)"
```

---

### Task 4: Register `plugin-maven-repo.zip` as a shipped asset (bundled `.br` + split zip)

**Files:**
- Modify: `composite-builds/build-deps-common/constants/src/main/java/org/adfa/constants/constants.kt`
- Modify: `composite-builds/build-logic/plugins/src/main/java/com/itsaky/androidide/plugins/AndroidIDEAssetsPlugin.kt`
- Modify: `app/build.gradle.kts` (`createAssetsZip` file list ~L455-464; `assembleV8Assets`/`assembleV7Assets` deps ~L486-503)

**Interfaces:**
- Consumes: Task 3's `assets/plugin-maven-repo.zip`.
- Produces: constant `PLUGIN_MAVEN_REPO_ZIP_NAME = "plugin-maven-repo.zip"` and `PLUGIN_MAVEN_REPO_ZIP_BR`; bundled common asset `data/common/plugin-maven-repo.zip.br`; split entry `plugin-maven-repo.zip` inside `assets-<arch>.zip`. (Task 5 consumes these.)

- [ ] **Step 1: Add the asset-name constants**

In `constants.kt`, after the Local-maven-repo block (`LOCAL_MAVEN_REPO_FOLDER_DEST`, ~L61):

```kotlin
// Plugin maven-repo overlay (plugin-api + plugin-builder coordinates + marker)
const val PLUGIN_MAVEN_REPO_ZIP_NAME = "plugin-maven-repo.zip"
const val PLUGIN_MAVEN_REPO_ZIP_BR = "${PLUGIN_MAVEN_REPO_ZIP_NAME}.br"
```

- [ ] **Step 2: Register the per-build brotli copier for bundled builds**

In `AndroidIDEAssetsPlugin.kt`, mirror `registerPluginArtifactsCopierTask` (~L80-107) with a new function, and call it from the `onVariants` block (after the plugin-artifacts copier registration, ~L75). The copier brotli-compresses `assets/plugin-maven-repo.zip` into `data/common/plugin-maven-repo.zip.br` when `hasBundledAssets(variant)`:

```kotlin
private fun registerPluginMavenRepoCopierTask(
	project: Project,
	variant: Variant,
) {
	val zip = project.rootProject.file("assets/plugin-maven-repo.zip")
	val taskName = "copy${variant.name.replaceFirstChar { it.uppercase() }}PluginMavenRepo"
	if (hasBundledAssets(variant)) {
		val task = project.tasks.register(taskName, AddBrotliFileToAssetsTask::class.java) {
			it.dependsOn(project.tasks.named("createPluginMavenRepoZip"))
			it.inputFile.set(zip)
		}
		variant.sources.assets?.addGeneratedSourceDirectory(task, AddBrotliFileToAssetsTask::outputDirectory)
	} else {
		val task = project.tasks.register(taskName, AddFileToAssetsTask::class.java) {
			it.dependsOn(project.tasks.named("createPluginMavenRepoZip"))
			it.inputFile.set(zip)
		}
		variant.sources.assets?.addGeneratedSourceDirectory(task, AddFileToAssetsTask::outputDirectory)
	}
}
```

Match the exact wiring of `registerPluginArtifactsCopierTask` (task property names, `baseAssetPath`/`data/common` default, `onVariants` call site). Call `registerPluginMavenRepoCopierTask(project, variant)` alongside the existing copier calls in `onVariants`.

- [ ] **Step 3: Add the split entry + assemble deps in `app/build.gradle.kts`**

In `createAssetsZip(arch)`, add `"plugin-maven-repo.zip"` to the `arrayOf(...)` file list (after `"plugin-artifacts.zip"`, ~L462). No `entryName` remap is needed (the `when` at ~L471 falls through to `else -> fileName`), so the entry name stays `plugin-maven-repo.zip`.

Add a `dependsOn("createPluginMavenRepoZip")` to both `assembleV8Assets` and `assembleV7Assets` (~L486-503), so the file exists before `createAssetsZip` runs (it throws `FileNotFoundException` on a missing file, ~L466-468).

- [ ] **Step 4: Verify the split asset packaging includes the new entry**

Run: `flox activate -d flox/local -- ./gradlew :app:assembleV8Assets`
Then: `unzip -l app/build/outputs/assets/assets-arm64-v8a.zip | grep plugin-maven-repo`
Expected: `plugin-maven-repo.zip` is listed as an entry.

- [ ] **Step 5: Commit**

```bash
cd ~/src/cogo/ADFA-4911
flox activate -d flox/local -- ./gradlew spotlessApply
git add composite-builds/build-deps-common/constants/src/main/java/org/adfa/constants/constants.kt \
        composite-builds/build-logic/plugins/src/main/java/com/itsaky/androidide/plugins/AndroidIDEAssetsPlugin.kt \
        app/build.gradle.kts
git commit -m "ADFA-4911: Ship plugin-maven-repo.zip as a bundled (.br) and split asset"
```

---

### Task 5: Merge the overlay into LOCAL_MAVEN_DIR on-device (both installers)

**Files:**
- Test: `app/src/test/java/com/itsaky/androidide/assets/ExtractZipToDirMergeTest.kt` (new)
- Modify: `app/src/main/java/com/itsaky/androidide/assets/BundledAssetsInstaller.kt` (~L56-71)
- Modify: `app/src/main/java/com/itsaky/androidide/assets/SplitAssetsInstaller.kt` (~L62-75)

**Interfaces:**
- Consumes: `AssetsInstallationHelper.extractZipToDir(srcStream, destDir)` (existing, L241-271 — creates dirs and copies without wiping); constants `PLUGIN_MAVEN_REPO_ZIP_NAME`, `PLUGIN_MAVEN_REPO_ZIP_BR`; `ToolsManager.getCommonAsset` (prefixes `data/common/`).

- [ ] **Step 1: Write the failing merge test**

`extractZipToDir` is the merge primitive: it must add overlay entries into a dir that already has files, without deleting the existing ones, and reject path traversal. Create `ExtractZipToDirMergeTest.kt`:

```kotlin
package com.itsaky.androidide.assets

import io.mockk.mockkObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtractZipToDirMergeTest {
	@Before
	fun setup() {
		mockkObject(AssetsInstallationHelper)
	}

	private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
		val bos = ByteArrayOutputStream()
		ZipOutputStream(bos).use { zip ->
			for ((name, body) in entries) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(body.toByteArray())
				zip.closeEntry()
			}
		}
		return ByteArrayInputStream(bos.toByteArray())
	}

	@Test
	fun `overlay merges without wiping existing files`() {
		val dest = Files.createTempDirectory("mvn").also {
			Files.createDirectories(it.resolve("com/foo/1.0"))
			Files.writeString(it.resolve("com/foo/1.0/foo-1.0.jar"), "harvested")
		}

		AssetsInstallationHelper.extractZipToDir(
			zipOf("com/itsaky/androidide/plugin-api/1.0.0/plugin-api-1.0.0.jar" to "fat"),
			dest,
		)

		assertTrue("harvested file must survive the merge",
			Files.exists(dest.resolve("com/foo/1.0/foo-1.0.jar")))
		assertEquals("fat",
			Files.readString(dest.resolve("com/itsaky/androidide/plugin-api/1.0.0/plugin-api-1.0.0.jar")))
	}

	@Test
	fun `rejects path traversal`() {
		val dest = Files.createTempDirectory("mvn")
		assertThrows(IllegalStateException::class.java) {
			AssetsInstallationHelper.extractZipToDir(zipOf("../evil.jar" to "x"), dest)
		}
	}
}
```

- [ ] **Step 2: Run the test to confirm it passes against the existing primitive**

Run: `flox activate -d flox/local -- ./gradlew :app:testV8DebugUnitTest --tests "com.itsaky.androidide.assets.ExtractZipToDirMergeTest"`
Expected: PASS both cases. (This pins the merge/no-wipe + traversal-guard contract the installers rely on. `extractZipToDir` already enforces the `..`/absolute-path check at L251-253.)

- [ ] **Step 3: Add the overlay to `BundledAssetsInstaller`**

Split `LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME` out of the shared archive arm (L56-71) into its own branch that extracts the harvested repo, then merges the plugin overlay in the same job:

```kotlin
GRADLE_DISTRIBUTION_ARCHIVE_NAME,
ANDROID_SDK_ZIP,
-> {
	val destDir = destinationDirForArchiveEntry(entryName).toPath()
	if (Files.exists(destDir)) {
		destDir.deleteRecursively()
	}
	Files.createDirectories(destDir)
	val assetPath = ToolsManager.getCommonAsset("$entryName.br")
	assets.open(assetPath).use { assetStream ->
		BrotliInputStream(assetStream).use { srcStream ->
			AssetsInstallationHelper.extractZipToDir(srcStream, destDir)
		}
	}
}

LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> {
	val destDir = destinationDirForArchiveEntry(entryName).toPath()
	if (Files.exists(destDir)) {
		destDir.deleteRecursively()
	}
	Files.createDirectories(destDir)
	// 1) harvested repo
	assets.open(ToolsManager.getCommonAsset("$entryName.br")).use { assetStream ->
		BrotliInputStream(assetStream).use { srcStream ->
			AssetsInstallationHelper.extractZipToDir(srcStream, destDir)
		}
	}
	// 2) plugin coordinate overlay -- merged (no wipe) into the same repo
	assets.open(ToolsManager.getCommonAsset(PLUGIN_MAVEN_REPO_ZIP_BR)).use { assetStream ->
		BrotliInputStream(assetStream).use { srcStream ->
			AssetsInstallationHelper.extractZipToDir(srcStream, destDir)
		}
	}
	logger.debug("Merged plugin coordinates into {}", destDir)
}
```

Add imports: `import org.adfa.constants.PLUGIN_MAVEN_REPO_ZIP_BR`.

- [ ] **Step 4: Add the overlay to `SplitAssetsInstaller`**

Split `LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME` out of the shared arm (L62-75). Extract the harvested repo from the entry stream, then read the `plugin-maven-repo.zip` entry from the already-open `zipFile` and merge:

```kotlin
GRADLE_DISTRIBUTION_ARCHIVE_NAME,
ANDROID_SDK_ZIP,
GRADLE_API_NAME_JAR_ZIP,
-> {
	val destDir = destinationDirForArchiveEntry(entry.name).toPath()
	if (Files.exists(destDir)) {
		destDir.deleteRecursively()
	}
	Files.createDirectories(destDir)
	AssetsInstallationHelper.extractZipToDir(zipInput, destDir)
}

LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME -> {
	val destDir = destinationDirForArchiveEntry(entry.name).toPath()
	if (Files.exists(destDir)) {
		destDir.deleteRecursively()
	}
	Files.createDirectories(destDir)
	// 1) harvested repo
	AssetsInstallationHelper.extractZipToDir(zipInput, destDir)
	// 2) plugin coordinate overlay from the split assets zip -- merged (no wipe)
	val overlay = zipFile.getEntry(PLUGIN_MAVEN_REPO_ZIP_NAME)
		?: throw FileNotFoundException(
			context.getString(R.string.err_asset_entry_not_found, PLUGIN_MAVEN_REPO_ZIP_NAME))
	zipFile.getInputStream(overlay).use { overlayInput ->
		AssetsInstallationHelper.extractZipToDir(overlayInput, destDir)
	}
	logger.debug("Merged plugin coordinates into {}", destDir)
}
```

Add imports: `import org.adfa.constants.PLUGIN_MAVEN_REPO_ZIP_NAME`. (`GRADLE_API_NAME_JAR_ZIP` stays in the shared arm; only `LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME` moves out.)

- [ ] **Step 5: Build both installers' module to confirm compilation**

Run: `flox activate -d flox/local -- ./gradlew :app:compileV8DebugKotlin`
Expected: BUILD SUCCESSFUL (constants resolve, imports correct).

- [ ] **Step 6: Commit**

```bash
cd ~/src/cogo/ADFA-4911
flox activate -d flox/local -- ./gradlew spotlessApply
git add app/src/test/java/com/itsaky/androidide/assets/ExtractZipToDirMergeTest.kt \
        app/src/main/java/com/itsaky/androidide/assets/BundledAssetsInstaller.kt \
        app/src/main/java/com/itsaky/androidide/assets/SplitAssetsInstaller.kt
git commit -m "ADFA-4911: Merge plugin coordinate overlay into localMvnRepository during onboarding"
```

---

### Task 6: Document the coordinate + version

**Files:**
- Modify: the Plugin API changelog added by ADFA-1713 (find with `git log --oneline | grep -i changelog`, or `find . -iname "*plugin*api*changelog*" -o -iname "CHANGELOG*" -path "*plugin*"`), or `plugin-api/README.md` if no changelog exists.

**Interfaces:** none (docs).

- [ ] **Step 1: Add the coordinate + build snippet**

Document that on-device plugins resolve, offline, with no `libs/`:

```kotlin
plugins {
	id("com.itsaky.androidide.plugins.build") version "1.0.0"
}
dependencies {
	compileOnly("com.itsaky.androidide:plugin-api:1.0.0")
}
```

Note the `plugin-api:1.0.0` coordinate is a fat jar (plugin-api + common + eventbus-events + idetooltips), injected into `localMvnRepository` at onboarding, and its version tracks the shipped jar.

- [ ] **Step 2: Commit**

```bash
cd ~/src/cogo/ADFA-4911
git add <the docs file>
git commit -m "ADFA-4911: Document the plugin-api:1.0.0 coordinate and coordinate-based plugin build"
```

---

### Task 7: End-to-end on-device verification (acceptance criteria)

**Files:** none (verification only). Requires an arm device/emulator (`adb devices -l | grep -v offline`; target `emulator-5554`).

- [ ] **Step 1: Build + install the debug APK and its split assets**

```bash
flox activate -d flox/local -- ./gradlew :app:assembleV8Debug :app:assembleV8Assets --parallel --max-workers=6
adb -s emulator-5554 install -r app/build/outputs/apk/v8/debug/app-v8-debug.apk
adb -s emulator-5554 push app/build/outputs/assets/assets-arm64-v8a.zip /sdcard/Download/assets-arm64-v8a.zip
```
Then launch the app and complete onboarding (asset installation).

- [ ] **Step 2: Verify the coordinates landed (AC #1)**

```bash
adb -s emulator-5554 shell "find /data/data/com.itsaky.androidide/files/home/maven/localMvnRepository -path '*plugin*' -name '*.pom' -o -path '*plugin*' -name '*.jar'"
```
Expected: the plugin-api jar+pom, plugin-builder jar+pom, and the `com.itsaky.androidide.plugins.build` marker pom, at their coordinate paths.

- [ ] **Step 3: Build a no-`libs/` plugin offline (AC #2)**

On-device (or via a Termux/gradle harness), create a minimal plugin project with **no** `libs/` dir:
```kotlin
// settings.gradle.kts resolves via COTGSettingsPlugin (localMvnRepository injected)
plugins { id("com.itsaky.androidide.plugins.build") version "1.0.0" }
dependencies { compileOnly("com.itsaky.androidide:plugin-api:1.0.0") }
```
Run `:assemblePluginDebug` with networking disabled. Expected: BUILD SUCCESSFUL, a `.cgp` produced, no network access.

- [ ] **Step 4: Record results on the Jira ticket**

`jira issue comment add ADFA-4911 "<verification output: coordinates present + offline .cgp build succeeded>"`

---

## Self-Review

**Spec coverage:** the 3 coordinates (Task 1-3), fat-jar merge of all 4 modules (Task 2), dependency-free plugin-api POM + real builder POM/marker (Tasks 1,3), sibling-asset shipping bundled+split (Task 4), the wipe/concurrency-safe overlay inside the localMvnRepository branch (Task 5), the merge/traversal test (Task 5), docs (Task 6), and all three acceptance criteria (Task 7). No spec requirement is unmapped.

**Placeholders:** none — every code/test/command step is concrete. The two empirically-risky names (the builder publish-task name; the `classes.jar` intermediate paths) each have an explicit discover/verify step (1.2, 2.2/2.3) that fails loudly on mismatch.

**Type/name consistency:** constant names `PLUGIN_MAVEN_REPO_ZIP_NAME` / `PLUGIN_MAVEN_REPO_ZIP_BR` are defined in Task 4 and consumed by the split/bundled branches in Task 5; the coordinate paths asserted in 3.3 match those verified on-device in 7.2; `extractZipToDir` signature matches its existing definition.
