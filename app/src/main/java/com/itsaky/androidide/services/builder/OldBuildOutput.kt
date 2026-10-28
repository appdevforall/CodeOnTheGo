package com.itsaky.androidide.services.builder

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The build output a project folder still holds after its output moved to app storage (ADFA-4930).
 * It no longer updates, so a file manager shows an old APK where users look for the new one, and it
 * takes the space a second copy of the output does. It is removed once, after the project's first
 * relocated initialization.
 */
object OldBuildOutput {
	/** Written in a project's tree once its old output is removed, so it is removed only once. */
	const val REMOVED_FILE = "old-output-removed"

	/**
	 * The stale folders of a relocated project: the `build/` of each project the synced model put in
	 * [tree], the root's `.gradle/` and `.kotlin/`, and `buildSrc/.kotlin/`. For a project in [tree],
	 * `build/` was Gradle's default before the move; a project whose build script sets its own build
	 * directory stays where it was, so a `build/` folder in it was never Gradle's and is kept. Only
	 * folders inside [rootDir] once symlinks are resolved, so a linked module folder cannot lead the
	 * deletion out of the project. Left alone: a symlink; a folder that is a project's directory or
	 * holds one; a folder that holds or lies in a build directory the model still uses;
	 * `buildSrc/build/`, as `buildSrc` is not in the model, so whether it kept that default cannot be
	 * told; and `buildSrc/.gradle/` and an included build's `.gradle/` and `.kotlin/`, which builds
	 * still use.
	 *
	 * @param rootDir the root project's directory.
	 * @param tree the project's tree, from [BuildOutputLocation.treeFor].
	 * @param projectDirs the directory of every project in the synced model.
	 * @param buildDirs the build directory of every project in the synced model.
	 */
	fun find(
		rootDir: File,
		tree: File,
		projectDirs: List<File>,
		buildDirs: List<File>,
	): List<File> {
		val root = rootDir.absoluteFile.normalize()
		val canonicalRoot = canonicalOrNull(root) ?: return emptyList()
		val projects = projectDirs.map { it.absoluteFile.normalize() }
		val inUse = buildDirs.map { it.absoluteFile.normalize() }
		val relocated = projects.zip(inUse).filter { (_, buildDir) -> BuildOutputLocation.isRelocated(buildDir, tree) }
		val candidates =
			relocated.map { (projectDir, _) -> File(projectDir, "build") } +
				listOf(".gradle", ".kotlin", "buildSrc/.kotlin").map { File(root, it) }
		return candidates.distinct().filter { dir ->
			canonicalOrNull(dir)?.startsWith(canonicalRoot) == true &&
				Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) &&
				projects.none { it.startsWith(dir) } &&
				inUse.none { it.startsWith(dir) || dir.startsWith(it) }
		}
	}

	/**
	 * Deletes what [find] returns, unless [tree] records that it was done before. It is recorded only
	 * once everything is gone, so a deletion that fails part-way is tried again at the next relocated
	 * initialization.
	 *
	 * @param tree the project's tree, from [BuildOutputLocation.treeFor].
	 * @return how many folders were deleted.
	 */
	fun removeOnce(
		tree: File,
		rootDir: File,
		projectDirs: List<File>,
		buildDirs: List<File>,
	): Int {
		val marker = File(tree, REMOVED_FILE)
		if (marker.exists()) return 0
		val stale = find(rootDir, tree, projectDirs, buildDirs)
		val removed = stale.count { deleteWithoutFollowingLinks(it) }
		if (removed == stale.size) {
			try {
				marker.createNewFile()
			} catch (e: IOException) {
				// Not recorded: the next relocated initialization tries again, and finds nothing to do.
			}
		}
		return removed
	}

	private fun canonicalOrNull(file: File): File? =
		try {
			file.canonicalFile
		} catch (e: IOException) {
			null
		}
}
