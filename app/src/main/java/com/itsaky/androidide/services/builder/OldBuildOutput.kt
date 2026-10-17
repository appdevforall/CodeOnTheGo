package com.itsaky.androidide.services.builder

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
	 * The stale folders of a relocated project: each Gradle project's `build/`, and the Gradle and
	 * Kotlin caches of the root and of `buildSrc`. Only folders Gradle makes by default, inside
	 * [rootDir]. Left alone: a symlink; a folder that is a project's directory or holds one; a folder
	 * that holds or lies in a build directory the model still uses; and `buildSrc/.gradle/` and
	 * included builds, which builds still use.
	 *
	 * @param rootDir the root project's directory.
	 * @param projectDirs the directory of every project in the synced model.
	 * @param buildDirs the build directory of every project in the synced model.
	 */
	fun find(
		rootDir: File,
		projectDirs: List<File>,
		buildDirs: List<File>,
	): List<File> {
		val root = rootDir.absoluteFile.normalize()
		val projects = projectDirs.map { it.absoluteFile.normalize() }
		val inUse = buildDirs.map { it.absoluteFile.normalize() }
		val candidates =
			projects.map { File(it, "build") } +
				listOf(".gradle", ".kotlin", "buildSrc/build", "buildSrc/.kotlin").map { File(root, it) }
		return candidates.distinct().filter { dir ->
			dir.startsWith(root) &&
				Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) &&
				projects.none { it.startsWith(dir) } &&
				inUse.none { it.startsWith(dir) || dir.startsWith(it) }
		}
	}

	/**
	 * Deletes what [find] returns, unless [tree] records that it was done before.
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
		val removed = find(rootDir, projectDirs, buildDirs).count(::deleteWithoutFollowingLinks)
		try {
			marker.createNewFile()
		} catch (e: IOException) {
			// Not recorded: the next relocated initialization tries again, and finds less to do.
		}
		return removed
	}

	/**
	 * Deletes [dir] and everything in it without following symlinks, so a link in a project's folder
	 * never leads the deletion out of it. A file that cannot be deleted is skipped, not fatal.
	 *
	 * @return whether [dir] is gone.
	 */
	internal fun deleteWithoutFollowingLinks(dir: File): Boolean {
		val start = dir.toPath()
		if (Files.notExists(start, LinkOption.NOFOLLOW_LINKS)) return true
		try {
			Files.walkFileTree(
				start,
				object : SimpleFileVisitor<Path>() {
					override fun visitFile(
						file: Path,
						attrs: BasicFileAttributes,
					): FileVisitResult {
						deleteQuietly(file)
						return FileVisitResult.CONTINUE
					}

					override fun visitFileFailed(
						file: Path,
						exc: IOException,
					): FileVisitResult = FileVisitResult.CONTINUE

					override fun postVisitDirectory(
						dir: Path,
						exc: IOException?,
					): FileVisitResult {
						deleteQuietly(dir)
						return FileVisitResult.CONTINUE
					}
				},
			)
		} catch (e: IOException) {
			return false
		}
		return Files.notExists(start, LinkOption.NOFOLLOW_LINKS)
	}

	private fun deleteQuietly(path: Path) {
		try {
			Files.deleteIfExists(path)
		} catch (e: IOException) {
			// Left in place; the folder holding it stays too.
		}
	}
}
