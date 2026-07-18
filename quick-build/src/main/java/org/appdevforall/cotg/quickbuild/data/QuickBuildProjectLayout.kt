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

	/** Roots the watch filter accepts events under (src/res/assets). */
	fun watchedRoots(): List<File>

	/** Exact files watched outside the roots (gradle config; changes invalidate). */
	fun watchedFiles(): List<File>
}

/**
 * Convention-based layout for the standard single-app-module project the templates
 * emit: sources in `src/main/{java,kotlin}`, resources in `src/main/res`, assets in
 * `src/main/assets`. Pure JVM on purpose.
 */
class DefaultQuickBuildProjectLayout(
	override val projectRoot: File,
	private val appModuleDir: File = File(projectRoot, "app"),
	private val classpath: List<File> = emptyList(),
) : QuickBuildProjectLayout {
	private val mainDir = File(appModuleDir, "src/main")

	override fun allSources(): List<File> =
		listOf(File(mainDir, "java"), File(mainDir, "kotlin"))
			.filter { it.isDirectory }
			.flatMap { root ->
				root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
			}.sorted()

	override fun resDirs(): List<File> = listOf(File(mainDir, "res")).filter { it.isDirectory }

	override fun assetRoots(): List<File> = listOf(File(mainDir, "assets"))

	override fun manifest(): File = File(mainDir, "AndroidManifest.xml")

	override fun compileClasspath(): List<File> = classpath

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
