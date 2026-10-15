package com.itsaky.androidide.services.builder

import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_BUILD_OUTPUT_DIR
import org.appdevforall.cotg.quickbuild.data.ProjectStorageKey
import java.io.File

/**
 * Where a project's Gradle build output goes when it is moved off the FUSE-backed project folder
 * (ADFA-4930): `<noBackupFilesDir>/gradle-build/<projectKey>/`, beside Quick Build's scratch and
 * keyed the same way. App-private f2fs makes warm builds 24-35% faster on the devices measured.
 */
object BuildOutputLocation {
	/** Parent of every project's tree, under `noBackupFilesDir`. */
	const val DIR_NAME = "gradle-build"

	/** Holds the project's path, so a sweep can find trees whose project is gone. */
	const val PROJECT_PATH_FILE = "project-path"

	private const val KOTLIN_PERSISTENT_DIR = "kotlin.project.persistent.dir"

	/**
	 * @param noBackupDir the app's `noBackupFilesDir`.
	 * @param projectDir the project's root directory.
	 * @return the project's tree; computed, not created.
	 */
	fun treeFor(
		noBackupDir: File,
		projectDir: File,
	): File = File(File(noBackupDir, DIR_NAME), ProjectStorageKey.of(projectDir))

	/**
	 * The Gradle arguments that send a project's build output into [tree]: every project's build
	 * directory (set by the init script plugin), the project cache that is otherwise `.gradle/`,
	 * and Kotlin's `.kotlin/`. Kotlin Gradle plugins that predate that property ignore it.
	 *
	 * Gradle refuses `--project-cache-dir` together with file-system watching enabled by
	 * `--watch-fs` (which [GradleBuildTuner] adds) or by `org.gradle.vfs.watch=true` in a
	 * project's `gradle.properties`, so these arguments end with `--no-watch-fs`. The last of the
	 * two flags wins and these arguments come after the tuner's. Nothing is lost: watching cannot
	 * run on Android, where Gradle's native file-events library does not load (ADFA-6545).
	 */
	fun gradleArgs(tree: File): List<String> =
		listOf(
			"-P$PROPERTY_BUILD_OUTPUT_DIR=${tree.absolutePath}",
			"--project-cache-dir",
			File(tree, "project-cache").absolutePath,
			"-P$KOTLIN_PERSISTENT_DIR=${File(tree, "kotlin").absolutePath}",
			"--no-watch-fs",
		)

	/**
	 * Creates [tree] and records [projectDir] in it.
	 *
	 * @return false when [tree] cannot be created; the build then keeps its output in the project.
	 */
	fun prepare(
		tree: File,
		projectDir: File,
	): Boolean {
		if (!tree.isDirectory && !tree.mkdirs()) return false
		val marker = File(tree, PROJECT_PATH_FILE)
		val path = projectDir.absolutePath
		if (!marker.isFile || marker.readText() != path) marker.writeText(path)
		return true
	}
}
