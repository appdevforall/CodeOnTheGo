package com.itsaky.androidide.services.builder

import com.itsaky.androidide.preferences.internal.BuildPreferences
import com.itsaky.androidide.projects.ProjectManagerImpl
import com.itsaky.androidide.projects.api.Workspace
import com.itsaky.androidide.projects.models.buildDir
import com.itsaky.androidide.tooling.api.GradlePluginConfig.PROPERTY_BUILD_OUTPUT_DIR
import com.itsaky.androidide.utils.ProjectStorageKey
import com.itsaky.androidide.utils.canonicalProjectLocation
import com.itsaky.androidide.utils.isDeletedProjectDirectory
import java.io.File
import java.io.IOException

/**
 * Where a project's Gradle build output goes when it is moved off the FUSE-backed project folder
 * (ADFA-4930): `<noBackupFilesDir>/gradle-build/<projectKey>/`, beside Quick Build's scratch and
 * keyed by the same function, from the project's canonical path. App-private f2fs makes warm builds
 * up to about twice as fast on the devices measured.
 *
 * The synced project model is the record of where output goes. A sync applies the user's choice;
 * a build follows the model's root build directory ([modelRelocated]), not the live switch, so it
 * writes where the model (APK listing file, classpath) looks, and a sync that fails leaves both
 * as they were. A change to the switch therefore waits for the next successful sync
 * ([syncPending]).
 */
object BuildOutputLocation {
	/** Parent of every project's tree, under `noBackupFilesDir`. */
	const val DIR_NAME = "gradle-build"

	/** Holds the project's path, so a sweep can find trees whose project is gone. */
	const val PROJECT_PATH_FILE = "project-path"

	private const val KOTLIN_PERSISTENT_DIR = "kotlin.project.persistent.dir"
	private const val WATCH_FS = "--watch-fs"
	private const val NO_WATCH_FS = "--no-watch-fs"
	private const val PROJECT_CACHE_DIR = "--project-cache-dir"

	/** Where Android mounts storage volumes: internal storage, and each SD card or USB drive. */
	private val STORAGE_ROOT = File("/storage")

	/** Whether the user currently asks for relocation; applied at the next sync. */
	fun isRequested(): Boolean = BuildPreferences.relocateBuildOutput

	/**
	 * @param noBackupDir the app's `noBackupFilesDir`.
	 * @param projectDir the project's root directory.
	 * @return the project's tree; computed, not created. Keyed by the canonical path, so every spelling
	 *   of the project's path (`/sdcard` against `/storage/emulated/0`) names the same tree.
	 */
	fun treeFor(
		noBackupDir: File,
		projectDir: File,
	): File = File(File(noBackupDir, DIR_NAME), ProjectStorageKey.of(File(projectDir.canonicalProjectLocation())))

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

	/** Whether a sync's or build's arguments relocate its output, from [gradleArgs]. */
	fun isRelocating(args: List<String>): Boolean = args.any { it.startsWith("-P$PROPERTY_BUILD_OUTPUT_DIR=") }

	/**
	 * Drops `--watch-fs` from a build's complete argument list when it also relocates the project
	 * cache, so the build does not fail whatever order the tuner's and these arguments are in.
	 */
	fun withoutWatchConflict(args: List<String>): List<String> = if (PROJECT_CACHE_DIR in args) args.filterNot { it == WATCH_FS } else args

	/**
	 * Creates [tree] and records [projectDir] in it.
	 *
	 * @return false when [tree] or its marker cannot be written; a sync then keeps output in the
	 *   project folder.
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
	 * Deletes the trees of [projectDir], matched by their [PROJECT_PATH_FILE] marker rather than by
	 * key, so a path spelled differently (`/sdcard` against `/storage/emulated/0`) still matches.
	 * Call it when the user deletes the project.
	 *
	 * @param keep a directory whose tree must survive, such as the loaded model's build directory.
	 * @return how many trees were deleted.
	 */
	fun removeTreesOf(
		noBackupDir: File,
		projectDir: File,
		keep: File? = null,
	): Int {
		val location = projectDir.canonicalProjectLocation()
		val kept = keep?.absoluteFile?.normalize()
		return deleteTreesWhere(noBackupDir) { tree, project ->
			project.canonicalProjectLocation() == location && (kept == null || !kept.startsWith(tree.absoluteFile.normalize()))
		}
	}

	/**
	 * Deletes the trees of projects that no longer exist, such as a project deleted, moved or renamed
	 * outside the IDE, or deleted with the folder holding it. A project on a volume that is not
	 * mounted is not gone, and a tree without a marker is left alone. Runs on a background thread at
	 * startup.
	 *
	 * @param volumesRoot where storage volumes are mounted; tests pass their own.
	 * @return how many trees were deleted.
	 */
	fun sweep(
		noBackupDir: File,
		volumesRoot: File = STORAGE_ROOT,
	): Int = deleteTreesWhere(noBackupDir) { _, project -> isGone(project, volumesRoot) }

	/**
	 * Whether [project] was deleted. With its parent folder still there, Recents' rule decides. With
	 * the parent missing too, the nearest folder that exists decides: a readable folder on a volume
	 * means the folders were deleted, while [volumesRoot] itself means the volume is not mounted.
	 */
	private fun isGone(
		project: File,
		volumesRoot: File,
	): Boolean {
		if (project.isDeletedProjectDirectory()) return true
		if (project.exists()) return false
		val nearest = generateSequence(project.parentFile) { it.parentFile }.firstOrNull { it.exists() } ?: return false
		return try {
			val ancestor = nearest.canonicalFile
			val root = volumesRoot.canonicalFile
			ancestor != root && ancestor.startsWith(root) && ancestor.isDirectory && ancestor.canRead()
		} catch (e: IOException) {
			false
		}
	}

	private fun deleteTreesWhere(
		noBackupDir: File,
		isOrphan: (tree: File, project: File) -> Boolean,
	): Int {
		val trees = File(noBackupDir, DIR_NAME).listFiles { file -> file.isDirectory } ?: return 0
		return trees.count { tree ->
			val project = projectOf(tree)
			project != null && isOrphan(tree, project) && deleteTree(tree)
		}
	}

	/**
	 * Deletes [tree] with its marker last: a deletion that stops part-way keeps the marker, so the
	 * next sweep can still tell whose tree it is and finish the job.
	 */
	private fun deleteTree(tree: File): Boolean {
		val marker = File(tree, PROJECT_PATH_FILE)
		val contents = tree.listFiles() ?: return false
		val deleted = contents.filter { it != marker }.map { it.deleteRecursively() }.all { it }
		return deleted && (!marker.exists() || marker.delete()) && tree.delete()
	}

	/** The project [tree] belongs to, from its marker, or null when the marker is missing or unreadable. */
	private fun projectOf(tree: File): File? =
		try {
			File(tree, PROJECT_PATH_FILE)
				.takeIf { it.isFile }
				?.readText()
				?.takeIf { it.isNotBlank() }
				?.let(::File)
		} catch (e: IOException) {
			null
		}

	/**
	 * Whether a successful initialization should delete the project's relocated output: its model
	 * builds into the project folder, so the tree is dead weight. Not while a Quick Build session is
	 * live, which keeps absolute paths into the tree (the proxy APK, its classes and payload) until
	 * it ends; the next project open deletes it instead.
	 *
	 * @param afterInit an initialization, with or without a sync, just succeeded.
	 * @param modelRelocated [modelRelocated] for the loaded model.
	 * @param quickBuildLive a Quick Build session is live.
	 */
	fun shouldDeleteTrees(
		afterInit: Boolean,
		modelRelocated: Boolean?,
		quickBuildLive: Boolean,
	): Boolean = afterInit && modelRelocated == false && !quickBuildLive

	/**
	 * Whether to offer a relocation sync the user deferred: it was tapped while a build held the
	 * Gradle slot, and now no build holds it.
	 *
	 * @param building the user-visible or internal build flag that just changed.
	 * @param deferred a sync was deferred.
	 * @param slotBusy any build still holds the Gradle slot.
	 */
	fun shouldOfferDeferredSync(
		building: Boolean?,
		deferred: Boolean,
		slotBusy: Boolean,
	): Boolean = building == false && deferred && !slotBusy

	/**
	 * Whether a synced model's build directory lies in [tree], meaning the sync that produced the
	 * model relocated build output.
	 *
	 * @param modelBuildDir the root project's build directory in the synced model.
	 * @param tree the project's tree, from [treeFor].
	 */
	fun isRelocated(
		modelBuildDir: File,
		tree: File,
	): Boolean = modelBuildDir.absoluteFile.normalize().startsWith(tree.absoluteFile.normalize())

	/**
	 * [isRelocated] for the synced model of the open project, or null when no model is loaded.
	 *
	 * @param noBackupDir the app's `noBackupFilesDir`.
	 * @param projectDir the open project's root directory.
	 * @param workspace the synced model of the open project.
	 */
	fun modelRelocated(
		noBackupDir: File,
		projectDir: File,
		workspace: Workspace? = ProjectManagerImpl.getInstance().workspace,
	): Boolean? = workspace?.let { isRelocated(it.rootProject.buildDir, treeFor(noBackupDir, projectDir)) }

	/**
	 * Whether the switch disagrees with the synced model, so the next
	 * sync would move the build output. False without a model: there is nothing to disagree with.
	 *
	 * @param modelRelocated [modelRelocated] for the open project.
	 */
	fun syncPending(
		modelRelocated: Boolean?,
		requested: Boolean = isRequested(),
	): Boolean = modelRelocated != null && modelRelocated != requested
}
