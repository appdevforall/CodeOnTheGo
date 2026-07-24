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
	 * AGP's `stableIds.txt` from the setup build's real resource processing
	 * (`pkg:type/name = 0x7f0xxxxx`), if the setup report carried one. Relinks pass this
	 * to `aapt2 link --stable-ids` so a relink of the project's own res/ - a strict
	 * subset of what the real build merged in, library AAR resources included - pins
	 * every resource to the numeric id the baseline manifest was compiled against,
	 * instead of letting aapt2's type-index assignment drift when a whole resource TYPE
	 * present in the baseline is absent from the relink (ADFA-4128 Bug 6). Null when the
	 * setup build didn't report one (older AGP, or a variant whose resource processing
	 * never produced the file).
	 */
	fun stableIdsFile(): File?

	/**
	 * Pre-compiled `.flat` resource units from the setup build's real AGP resource
	 * processing: the project's own `intermediates/merged_res/` closure (which
	 * transitively carries every dependency AAR's VALUES resources - styles, themes,
	 * colors, dimens, strings, attrs) plus each resource-providing AAR's separately
	 * -compiled FILE-based resources (layouts, drawables, anims, ...). A relink of the
	 * project's own res/ alone can't resolve a resource a dependency AAR provides - e.g.
	 * Material3's `Theme.Material3.DayNight.NoActionBar`, which a Material3 template's own
	 * `themes.xml` extends - because the project's own res/ never declares it (ADFA-4128
	 * Bug 8). Passed to `aapt2 link` as `-R` overlays, ordered BEFORE the relink's own
	 * fresh compile (see `Aapt2Link`'s KDoc for why order matters). Empty when the setup
	 * build didn't report any (older AGP, or a variant whose resource processing never
	 * produced them) - relinks then fall back to the pre-fix behavior.
	 */
	fun libraryResourceFlats(): List<File>

	/** Roots the watch filter accepts events under (src/res/assets). */
	fun watchedRoots(): List<File>

	/** Exact files watched outside the roots (gradle config; changes invalidate). */
	fun watchedFiles(): List<File>
}

/**
 * Convention-based layout for the standard single-app-module project the templates
 * emit: sources in `src/main/{java,kotlin}`, resources in `src/main/res`, assets in
 * `src/main/assets`. Pure JVM on purpose.
 *
 * @param extraSourceRoots additional source roots the setup build reported (`setup.json`
 *   `sourceRoots`) - in practice the KSP/kapt GENERATED roots. Without them a project
 *   using an API-generating processor (Dagger and kin) cannot hot-compile at all: user
 *   code references generated classes the daemon has neither a source nor a classpath
 *   entry for. They are compiled but deliberately NOT watched - Gradle owns `build/`,
 *   and watching it would feed the loop its own output.
 * @param stableIdsFile the setup build's reported AGP stable-ids file (`setup.json`
 *   `stableIdsPath`), or null when it didn't report one. See [QuickBuildProjectLayout.stableIdsFile].
 * @param libraryResourceFlats the setup build's reported library-resource `.flat` units
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

	override fun watchedRoots(): List<File> = listOf(File(appModuleDir, "src"))

	override fun watchedFiles(): List<File> =
		listOf(
			File(projectRoot, "build.gradle"),
			File(projectRoot, "build.gradle.kts"),
			File(projectRoot, "settings.gradle"),
			File(projectRoot, "settings.gradle.kts"),
			File(projectRoot, "gradle.properties"),
			File(projectRoot, "gradle/libs.versions.toml"),
			File(appModuleDir, "build.gradle"),
			File(appModuleDir, "build.gradle.kts"),
		)
}
