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
 * Deletes [dir] and everything in it without following symlinks, so a link inside never leads the
 * deletion out of [dir]. A file that cannot be deleted is skipped, not fatal.
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
