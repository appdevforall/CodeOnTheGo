package org.appdevforall.cotg.quickbuild.data

import java.io.File

/**
 * What the quick path needs to know about the user project's shape.
 *
 * Convention-based, for the standard single-app-module project the templates emit: sources in
 * `src/main/{java,kotlin}`, resources in `src/main/res`, assets in `src/main/assets`. Pure
 * `File` arithmetic over those conventions, so tests build one over a temp dir rather than
 * faking it.
 *
 * @property projectRoot the user project's root directory, which the watched gradle config
 *   files and the module scan hang off.
 * @property appModuleDir the single app module's directory, whose `src/main` supplies every
 *   convention path and which is treated as a module even if the scan misses it.
 * @property classpath compile classpath handed straight to [compileClasspath], unmodified.
 * @property extraSourceRoots extra source roots from the proxy app build (the KSP/kapt generated
 *   roots, without which an annotation-processing project cannot hot-compile at all), compiled
 *   but deliberately not watched because Gradle owns `build/`.
 * @property stableIdsFile AGP's `stableIds.txt`, passed to `aapt2 link --stable-ids` so aapt2's
 *   type-index assignment cannot drift when a baseline resource type is absent from the relink;
 *   null when the proxy app build reported none, which relinks unpinned.
 * @property libraryResourceFlats pre-compiled `.flat` resource units from the proxy app build,
 *   passed to `aapt2 link` as `-R` overlays so a relink can resolve a resource only a dependency
 *   AAR declares (e.g. Material3's `Theme.Material3.DayNight.NoActionBar`).
 */
class QuickBuildProjectLayout(
	val projectRoot: File,
	private val appModuleDir: File = File(projectRoot, "app"),
	private val classpath: List<File> = emptyList(),
	private val extraSourceRoots: List<File> = emptyList(),
	private val stableIdsFile: File? = null,
	private val libraryResourceFlats: List<File> = emptyList(),
) {
	private val mainDir = File(appModuleDir, "src/main")

	/**
	 * Every `.kt`/`.java` under the app module's main source roots: `src/main/java`,
	 * `src/main/kotlin`, and [extraSourceRoots].
	 *
	 * @return existing `.kt`/`.java` files, deduplicated (the roots can overlap) and sorted so the
	 *   daemon sees a stable order; walks the filesystem on each call, so hold it for a build.
	 */
	fun allSources(): List<File> =
		(listOf(File(mainDir, "java"), File(mainDir, "kotlin")) + extraSourceRoots)
			.map { it.absoluteFile.normalize() }
			.distinct()
			.filter { it.isDirectory }
			.flatMap { root ->
				root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
			}.distinct()
			.sorted()

	/**
	 * The app module's resource directories, to recompile and relink.
	 *
	 * @return `src/main/res` when it exists, else empty - a project may legitimately have none.
	 */
	fun resDirs(): List<File> = listOf(File(mainDir, "res")).filter { it.isDirectory }

	/**
	 * The app module's asset roots, whose files ship in the payload zip.
	 *
	 * @return `src/main/assets`, listed whether or not it exists - it is a prefix for matching
	 *   changed files, not a directory to walk.
	 */
	fun assetRoots(): List<File> = listOf(File(mainDir, "assets"))

	/**
	 * The app module's `AndroidManifest.xml`.
	 *
	 * @return `src/main/AndroidManifest.xml`, unchecked; a relink surfaces a missing manifest
	 *   with aapt2's own error.
	 */
	fun manifest(): File = File(mainDir, "AndroidManifest.xml")

	/**
	 * Compile classpath for the daemon (library jars/AARs' classes).
	 *
	 * @return the [classpath] given at construction, order preserved - it matters for duplicate
	 *   classes.
	 */
	fun compileClasspath(): List<File> = classpath

	/** @return the [stableIdsFile] given at construction; null when none was reported. */
	fun stableIdsFile(): File? = stableIdsFile

	/** @return the [libraryResourceFlats] given at construction; empty when none were reported. */
	fun libraryResourceFlats(): List<File> = libraryResourceFlats

	/**
	 * Roots the watch filter accepts events under (src/res/assets). Every module's `src`, not
	 * just the app module's: a library edit must be seen so it rebaselines, rather than firing
	 * no event and silently not reloading. The classifier still live-reloads only
	 * [liveReloadScope]; other-module edits route to a full build.
	 *
	 * @return one `src` per discovered module, existing or not - the watcher skips the misses.
	 */
	fun watchedRoots(): List<File> = moduleDirs().map { File(it, "src") }

	/**
	 * Exact files watched outside the roots (gradle config; changes invalidate).
	 *
	 * @return the root's settings/properties/version-catalog files plus both build-script
	 *   spellings for every module, listed unconditionally - only the existing ones are polled.
	 */
	fun watchedFiles(): List<File> =
		listOf(
			File(projectRoot, "settings.gradle"),
			File(projectRoot, "settings.gradle.kts"),
			File(projectRoot, "gradle.properties"),
			File(projectRoot, "gradle/libs.versions.toml"),
		) +
			moduleDirs().flatMap {
				listOf(File(it, "build.gradle"), File(it, "build.gradle.kts"))
			}

	/**
	 * The source scope the live reload path can build incrementally - the app module's. A
	 * watched change outside it belongs to another module and must go through a proxy app
	 * rebuild (see [org.appdevforall.cotg.quickbuild.domain.classify.ChangeClassifier]).
	 *
	 * @return the app module's `src` alone; a change under none of them routes to a full build.
	 */
	fun liveReloadScope(): List<File> = listOf(File(appModuleDir, "src"))

	/**
	 * Finds every Gradle module dir (one holding a `build.gradle[.kts]`) by a shallow walk,
	 * always including the app module. Skips `build/` and hidden dirs, and bounds depth to
	 * keep the one-time session-start scan cheap. Errs toward including too much: a spurious
	 * module only costs a rebaseline, while a missed one silently drops its edits.
	 *
	 * @return the app module first, then each directory found, deduplicated; modules nested
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
