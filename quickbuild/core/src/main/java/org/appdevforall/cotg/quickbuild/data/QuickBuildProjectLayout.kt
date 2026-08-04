package org.appdevforall.cotg.quickbuild.data

import java.io.File

/**
 * What the quick path needs to know about the user project's shape.
 *
 * An interface so executor and session-manager tests run against a temp-dir fake, and the app
 * side can later swap in a project-model-backed implementation without touching the pipeline.
 */
interface QuickBuildProjectLayout {
	/** Root directory of the user project. */
	val projectRoot: File

	/**
	 * Every `.kt`/`.java` under the app module's main source roots.
	 *
	 * @return the sources, deduplicated and in a stable order. Walks the filesystem on each
	 *   call, so callers hold the result for a build rather than re-asking per step.
	 */
	fun allSources(): List<File>

	/**
	 * The app module's resource directories, to recompile and relink.
	 *
	 * @return only directories that currently exist; empty for a project with no `res/`.
	 */
	fun resDirs(): List<File>

	/**
	 * The app module's asset roots, whose files ship in the payload zip.
	 *
	 * @return the roots, which need not exist - they are prefixes for matching changed files,
	 *   not directories to walk.
	 */
	fun assetRoots(): List<File>

	/**
	 * The app module's `AndroidManifest.xml`.
	 *
	 * @return the conventional path, whether or not the file exists; a relink surfaces a
	 *   missing manifest with aapt2's own error.
	 */
	fun manifest(): File

	/**
	 * Compile classpath for the daemon (library jars/AARs' classes).
	 *
	 * @return the entries in classpath order, which matters for duplicate classes.
	 */
	fun compileClasspath(): List<File>

	/**
	 * AGP's `stableIds.txt` from the proxy app build (`pkg:type/name = 0x7f0xxxxx`), passed to
	 * `aapt2 link --stable-ids`. It pins every resource to the numeric id the baseline
	 * manifest was compiled against, so aapt2's type-index assignment cannot drift when a
	 * resource type present in the baseline is absent from the relink. Null when the proxy app
	 * build did not report one.
	 *
	 * @return the mapping file, or null to relink unpinned - correct only while no baseline
	 *   resource type is missing from the relink.
	 */
	fun stableIdsFile(): File?

	/**
	 * Pre-compiled `.flat` resource units from the proxy app build - the project's `merged_res/`
	 * closure (carrying every dependency AAR's values resources) plus each resource-providing
	 * AAR's compiled file resources. Without them a relink cannot resolve a resource only a
	 * dependency declares, e.g. Material3's `Theme.Material3.DayNight.NoActionBar`.
	 *
	 * @return the units, passed to `aapt2 link` as `-R` overlays ordered before the relink's own
	 *   fresh compile (`Aapt2Link`'s KDoc explains why order matters). Empty when the proxy app
	 *   build produced none.
	 */
	fun libraryResourceFlats(): List<File>

	/**
	 * Roots the watch filter accepts events under (src/res/assets).
	 *
	 * @return directories to watch recursively. Broader than [liveReloadScope] on purpose, so
	 *   an edit in another module is seen and can force a rebaseline.
	 */
	fun watchedRoots(): List<File>

	/**
	 * Exact files watched outside the roots (gradle config; changes invalidate).
	 *
	 * @return individual file paths, not directories. They need not exist - a project has only
	 *   one of `build.gradle` / `build.gradle.kts`, and both are listed.
	 */
	fun watchedFiles(): List<File>

	/**
	 * The source scope the live reload path can build incrementally - the app module's. A
	 * watched change outside it belongs to another module and must go through a proxy app
	 * rebuild (see [org.appdevforall.cotg.quickbuild.domain.ChangeClassifier]). Narrower than
	 * [watchedRoots], which spans every module's `src` so a library edit is seen rather than
	 * silently dropped.
	 *
	 * @return the in-scope roots; a changed file under none of them routes to a full build.
	 */
	fun liveReloadScope(): List<File>
}

/**
 * Convention-based layout for the standard single-app-module project the templates emit:
 * sources in `src/main/{java,kotlin}`, resources in `src/main/res`, assets in
 * `src/main/assets`.
 *
 * @property projectRoot the user project's root directory, which the watched gradle config
 *   files and the module scan hang off.
 * @property appModuleDir the single app module's directory; its `src/main` supplies every
 *   convention path, and it is always treated as a module even if the scan misses it.
 * @property classpath compile classpath handed straight to [compileClasspath], unmodified.
 * @property extraSourceRoots extra source roots from the proxy app build (`setup.json`
 *   `sourceRoots`), in practice the KSP/kapt generated roots. Without them a project using an
 *   API-generating processor (Dagger and kin) cannot hot-compile at all. They are compiled
 *   but deliberately not watched - Gradle owns `build/`, and watching it would feed the loop
 *   its own output.
 * @property stableIdsFile see [QuickBuildProjectLayout.stableIdsFile]; null when the proxy app
 *   build did not report one.
 * @property libraryResourceFlats see [QuickBuildProjectLayout.libraryResourceFlats]; empty when
 *   the proxy app build did not report any.
 */
class DefaultQuickBuildProjectLayout(
	override val projectRoot: File,
	private val appModuleDir: File = File(projectRoot, "app"),
	private val classpath: List<File> = emptyList(),
	private val extraSourceRoots: List<File> = emptyList(),
	private val stableIdsFile: File? = null,
	private val libraryResourceFlats: List<File> = emptyList(),
) : QuickBuildProjectLayout {
	private val mainDir = File(appModuleDir, "src/main")

	/**
	 * Walks `src/main/java`, `src/main/kotlin`, and [extraSourceRoots] for compilable sources.
	 *
	 * @return existing `.kt`/`.java` files, normalized, deduplicated (the roots can overlap),
	 *   and sorted so the daemon sees a stable order across builds.
	 */
	override fun allSources(): List<File> =
		(listOf(File(mainDir, "java"), File(mainDir, "kotlin")) + extraSourceRoots)
			.map { it.absoluteFile.normalize() }
			.distinct()
			.filter { it.isDirectory }
			.flatMap { root ->
				root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
			}.distinct()
			.sorted()

	/** @return `src/main/res` when it exists, else empty - a project may legitimately have none. */
	override fun resDirs(): List<File> = listOf(File(mainDir, "res")).filter { it.isDirectory }

	/** @return `src/main/assets`, listed whether or not it exists (it is a match prefix). */
	override fun assetRoots(): List<File> = listOf(File(mainDir, "assets"))

	/** @return `src/main/AndroidManifest.xml`, unchecked. */
	override fun manifest(): File = File(mainDir, "AndroidManifest.xml")

	/** @return the [classpath] given at construction, order preserved. */
	override fun compileClasspath(): List<File> = classpath

	/** @return the [stableIdsFile] given at construction; null when none was reported. */
	override fun stableIdsFile(): File? = stableIdsFile

	/** @return the [libraryResourceFlats] given at construction; empty when none were reported. */
	override fun libraryResourceFlats(): List<File> = libraryResourceFlats

	/**
	 * Every module's `src`, not just the app module's: a library edit must be seen so it
	 * rebaselines, rather than firing no event and silently not reloading. The classifier still
	 * live-reloads only [liveReloadScope]; other-module edits route to a full build.
	 *
	 * @return one `src` per discovered module, existing or not - the watcher skips the misses.
	 */
	override fun watchedRoots(): List<File> = moduleDirs().map { File(it, "src") }

	/**
	 * @return the root's settings/properties/version-catalog files plus both build-script
	 *   spellings for every module. Listed unconditionally; only the ones that exist are polled.
	 */
	override fun watchedFiles(): List<File> =
		listOf(
			File(projectRoot, "settings.gradle"),
			File(projectRoot, "settings.gradle.kts"),
			File(projectRoot, "gradle.properties"),
			File(projectRoot, "gradle/libs.versions.toml"),
		) +
			moduleDirs().flatMap {
				listOf(File(it, "build.gradle"), File(it, "build.gradle.kts"))
			}

	/** @return the app module's `src` alone - the only scope this path builds incrementally. */
	override fun liveReloadScope(): List<File> = listOf(File(appModuleDir, "src"))

	/**
	 * Finds every Gradle module dir (one holding a `build.gradle[.kts]`) by a shallow walk,
	 * always including the app module. Skips `build/` and hidden dirs, and bounds depth to
	 * keep the one-time session-start scan cheap. Errs toward including too much: a spurious
	 * module only costs a rebaseline, while a missed one silently drops its edits.
	 *
	 * @return the app module first, then each directory found, deduplicated. Modules nested
	 *   deeper than [MODULE_SCAN_MAX_DEPTH] are simply absent.
	 */
	private fun moduleDirs(): List<File> {
		val dirs = LinkedHashSet<File>()
		dirs.add(appModuleDir)
		projectRoot
			.walkTopDown()
			.maxDepth(MODULE_SCAN_MAX_DEPTH)
			.onEnter { it.name != "build" && !it.name.startsWith(".") }
			.forEach {
				if (it.isDirectory &&
					(File(it, "build.gradle").isFile || File(it, "build.gradle.kts").isFile)
				) {
					dirs.add(it)
				}
			}
		return dirs.toList()
	}

	private companion object {
		// `:a:b:c:d`-deep module paths are rare; a deeper reactor just watches less of its
		// tail, which stays correct - those edits are outside the live reload path anyway.
		const val MODULE_SCAN_MAX_DEPTH = 4
	}
}
