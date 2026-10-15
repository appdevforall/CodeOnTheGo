package com.itsaky.androidide.services.builder

import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_BUILD_OUTPUT_DIR
import com.itsaky.androidide.utils.FeatureFlags
import com.itsaky.androidide.utils.ProjectStorageKey
import java.io.File
import java.io.IOException

/**
 * Where a project's Gradle build output goes when it is moved off the FUSE-backed project folder
 * (ADFA-4930): `<noBackupFilesDir>/gradle-build/<projectKey>/`, beside Quick Build's scratch and
 * keyed the same way. App-private f2fs makes warm builds 24-35% faster on the devices measured.
 *
 * A sync applies the user's choice and records it in the project's cache directory; builds follow
 * that record, not the live switch, so their output is always where the synced model says it is.
 * A change to the switch therefore waits for the next sync ([syncPending]).
 */
object BuildOutputLocation {
	/** Parent of every project's tree, under `noBackupFilesDir`. */
	const val DIR_NAME = "gradle-build"

	/** Holds the project's path, so a sweep can find trees whose project is gone. */
	const val PROJECT_PATH_FILE = "project-path"

	/** Present in a project's cache directory when its last sync relocated build output. */
	const val SYNCED_STATE_FILE = "build-output-relocated"

	private const val KOTLIN_PERSISTENT_DIR = "kotlin.project.persistent.dir"
	private const val WATCH_FS = "--watch-fs"
	private const val NO_WATCH_FS = "--no-watch-fs"
	private const val PROJECT_CACHE_DIR = "--project-cache-dir"

	/** Whether the switch exists at all: early access only (REVIEW.md section 12). */
	fun isAvailable(): Boolean = FeatureFlags.isExperimentsEnabled

	/** Whether the user currently asks for relocation; applied at the next sync. */
	fun isRequested(): Boolean = isAvailable() && BuildPreferences.relocateBuildOutput

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
	 * They end with `--no-watch-fs`, which overrides `org.gradle.vfs.watch=true` in a project's
	 * `gradle.properties`: Gradle refuses `--project-cache-dir` with file-system watching turned on.
	 * [withoutWatchConflict] removes the tuner's `--watch-fs` wherever it sits. Nothing is lost:
	 * watching cannot run on Android, where Gradle's native file-events library does not load
	 * (ADFA-6545).
	 */
	fun gradleArgs(tree: File): List<String> =
		listOf(
			"-P$PROPERTY_BUILD_OUTPUT_DIR=${tree.absolutePath}",
			PROJECT_CACHE_DIR,
			File(tree, "project-cache").absolutePath,
			"-P$KOTLIN_PERSISTENT_DIR=${File(tree, "kotlin").absolutePath}",
			NO_WATCH_FS,
		)

	/**
	 * Drops `--watch-fs` from a build's complete argument list when it also relocates the project
	 * cache, so the build does not fail whatever order the tuner's and these arguments are in.
	 */
	fun withoutWatchConflict(args: List<String>): List<String> = if (PROJECT_CACHE_DIR in args) args.filterNot { it == WATCH_FS } else args

	/**
	 * Creates [tree] and records [projectDir] in it.
	 *
	 * @return false when [tree] or its marker cannot be written; the build then keeps its output
	 *   in the project folder.
	 */
	fun prepare(
		tree: File,
		projectDir: File,
	): Boolean {
		if (!tree.isDirectory && !tree.mkdirs()) return false
		val marker = File(tree, PROJECT_PATH_FILE)
		val path = projectDir.absolutePath
		return try {
			if (!marker.isFile || marker.readText() != path) marker.writeText(path)
			true
		} catch (e: IOException) {
			false
		}
	}

	/**
	 * Whether the project's last sync relocated its build output. False when nothing was recorded:
	 * every sync before this feature built into the project folder.
	 *
	 * @param projectCacheDir the project's cache directory (`<project>/.cg`).
	 */
	fun syncedState(projectCacheDir: File): Boolean = File(projectCacheDir, SYNCED_STATE_FILE).isFile

	/**
	 * Records what a sync of the project applied.
	 *
	 * @return false when the record cannot be written.
	 */
	fun recordSyncedState(
		projectCacheDir: File,
		relocated: Boolean,
	): Boolean {
		val file = File(projectCacheDir, SYNCED_STATE_FILE)
		return try {
			if (relocated) {
				(projectCacheDir.isDirectory || projectCacheDir.mkdirs()) && (file.isFile || file.createNewFile())
			} else {
				!file.exists() || file.delete()
			}
		} catch (e: IOException) {
			false
		}
	}

	/**
	 * Whether the switch (or the experiments flag) has changed since the project's last sync, so the
	 * next sync would move the build output.
	 */
	fun syncPending(
		projectCacheDir: File,
		requested: Boolean = isRequested(),
	): Boolean = syncedState(projectCacheDir) != requested
}
