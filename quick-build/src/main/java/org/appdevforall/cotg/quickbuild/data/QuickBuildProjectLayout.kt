package org.appdevforall.cotg.quickbuild.data

import java.io.File

/**
 * What the quick path needs to know about the user project's shape. Behind an
 * interface so the executor/session-manager tests run against a temp-dir fake and the
 * app side can later swap in a project-model-backed implementation without touching
 * the pipeline.
 */
interface QuickBuildProjectLayout {
	val projectRoot: File

	/** Every `.kt`/`.java` under the app module's main source roots. */
	fun allSources(): List<File>

	fun resDirs(): List<File>

	fun assetRoots(): List<File>

	fun manifest(): File

	/** Compile classpath for the daemon (library jars/AARs' classes). */
	fun compileClasspath(): List<File>

	/**
	 * AGP's `stableIds.txt` from the proxy app build's real resource processing
	 * (`pkg:type/name = 0x7f0xxxxx`), if the proxy app report carried one. Relinks pass this
	 * to `aapt2 link --stable-ids` so a relink of the project's own res/ - a strict
	 * subset of what the real build merged in, library AAR resources included - pins
	 * every resource to the numeric id the baseline manifest was compiled against,
	 * instead of letting aapt2's type-index assignment drift when a whole resource TYPE
	 * present in the baseline is absent from the relink (ADFA-4128 Bug 6). Null when the
	 * proxy app build didn't report one (older AGP, or a variant whose resource processing
	 * never produced the file).
	 */
	fun stableIdsFile(): File?

	/**
	 * Pre-compiled `.flat` resource units from the proxy app build's real AGP resource
	 * processing: the project's own `intermediates/merged_res/` closure (which
	 * transitively carries every dependency AAR's VALUES resources - styles, themes,
	 * colors, dimens, strings, attrs) plus each resource-providing AAR's separately
	 * -compiled FILE-based resources (layouts, drawables, anims, ...). A relink of the
	 * project's own res/ alone can't resolve a resource a dependency AAR provides - e.g.
	 * Material3's `Theme.Material3.DayNight.NoActionBar`, which a Material3 template's own
	 * `themes.xml` extends - because the project's own res/ never declares it (ADFA-4128
	 * Bug 8). Passed to `aapt2 link` as `-R` overlays, ordered BEFORE the relink's own
	 * fresh compile (see `Aapt2Link`'s KDoc for why order matters). Empty when the proxy app
	 * build didn't report any (older AGP, or a variant whose resource processing never
	 * produced them) - relinks then see the project's own res/ alone.
	 */
	fun libraryResourceFlats(): List<File>

	/** Roots the watch filter accepts events under (src/res/assets). */
	fun watchedRoots(): List<File>

	/** Exact files watched outside the roots (gradle config; changes invalidate). */
	fun watchedFiles(): List<File>

	/**
	 * The app module's source scope the live reload path can incrementally build. A watched
	 * code/resource/asset change OUTSIDE this scope is another module's and must go through a proxy app rebuild
	 * (see [org.appdevforall.cotg.quickbuild.domain.ChangeClassifier]). Distinct from
	 * [watchedRoots], which spans EVERY module's `src` (so a library edit is seen, not
	 * silently dropped) - only the app module's slice of that is live-reload-eligible.
	 */
	fun liveReloadScope(): List<File>
}

/**
 * Convention-based layout for the standard single-app-module project the templates
 * emit: sources in `src/main/{java,kotlin}`, resources in `src/main/res`, assets in
 * `src/main/assets`. Pure JVM on purpose.
 *
 * @param extraSourceRoots additional source roots the proxy app build reported (`setup.json`
 *   `sourceRoots`) - in practice the KSP/kapt GENERATED roots. Without them a project
 *   using an API-generating processor (Dagger and kin) cannot hot-compile at all: user
 *   code references generated classes the daemon has neither a source nor a classpath
 *   entry for. They are compiled but deliberately NOT watched - Gradle owns `build/`,
 *   and watching it would feed the loop its own output.
 * @param stableIdsFile the proxy app build's reported AGP stable-ids file (`setup.json`
 *   `stableIdsPath`), or null when it didn't report one. See [QuickBuildProjectLayout.stableIdsFile].
 * @param libraryResourceFlats the proxy app build's reported library-resource `.flat` units
 *   (`setup.json` `libraryResourcePaths`), or empty when it didn't report any. See
 *   [QuickBuildProjectLayout.libraryResourceFlats].
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

	override fun allSources(): List<File> =
		(listOf(File(mainDir, "java"), File(mainDir, "kotlin")) + extraSourceRoots)
			.map { it.absoluteFile.normalize() }
			.distinct()
			.filter { it.isDirectory }
			.flatMap { root ->
				root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
			}.distinct()
			.sorted()

	override fun resDirs(): List<File> = listOf(File(mainDir, "res")).filter { it.isDirectory }

	override fun assetRoots(): List<File> = listOf(File(mainDir, "assets"))

	override fun manifest(): File = File(mainDir, "AndroidManifest.xml")

	override fun compileClasspath(): List<File> = classpath

	override fun stableIdsFile(): File? = stableIdsFile

	override fun libraryResourceFlats(): List<File> = libraryResourceFlats

	// Watch EVERY module's src (not just the app module's): in a multi-module project a
	// feature/library edit must be SEEN so it rebaselines, instead of firing no event and
	// being silently not reloaded. The classifier still live-reloads only [liveReloadScope]
	// (the app module); other-module edits route to a full build.
	override fun watchedRoots(): List<File> = moduleDirs().map { File(it, "src") }

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

	override fun liveReloadScope(): List<File> = listOf(File(appModuleDir, "src"))

	/**
	 * Every Gradle module dir (a dir holding a `build.gradle[.kts]`), always including the
	 * app module, discovered by a shallow filesystem walk. Skips `build/` intermediates and
	 * hidden dirs; bounded depth keeps the one-time session-start scan cheap even on a
	 * deeply-nested reactor. Over-inclusion is harmless (a non-existent `src` is filtered by
	 * the watcher; a stray module's edit merely rebaselines); under-inclusion would resurrect
	 * the silent-drop bug, so this errs toward watching more.
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
		// `:a:b:c:d`-deep module paths are rare; deeper reactors just watch a bit less of
		// their tail (still correct - those edits fall to the periodic mtime sweep / are
		// out of the single-module live reload path anyway).
		const val MODULE_SCAN_MAX_DEPTH = 4
	}
}
