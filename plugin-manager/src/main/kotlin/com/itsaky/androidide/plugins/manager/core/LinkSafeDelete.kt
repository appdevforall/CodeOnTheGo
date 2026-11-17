package com.itsaky.androidide.plugins.manager.core

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private val log = LoggerFactory.getLogger("LinkSafeDelete")

/**
 * Deletes this file or folder tree without ever following a link: a link, the root included, is
 * deleted as a link and its target is left alone. File.deleteRecursively() follows a link nested in
 * the tree and empties its target, and plugin code can make a link anywhere in the app's storage.
 *
 * True when nothing is left, as with deleteRecursively(), so true for a path that doesn't exist. Keeps
 * going past an entry it can't delete, as deleteRecursively() does.
 */
internal fun File.deleteWithoutFollowingLinks(): Boolean {
	val root = toPath()
	if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
	// walkFileTree without FOLLOW_LINKS hands a link to visitFile, never into its target.
	val visitor =
		object : SimpleFileVisitor<Path>() {
			override fun visitFile(
				file: Path,
				attrs: BasicFileAttributes,
			) = tryDelete(file)

			override fun visitFileFailed(
				file: Path,
				exc: IOException,
			) = tryDelete(file)

			override fun postVisitDirectory(
				dir: Path,
				exc: IOException?,
			) = tryDelete(dir)

			private fun tryDelete(path: Path): FileVisitResult {
				try {
					Files.deleteIfExists(path)
				} catch (e: IOException) {
					log.debug("Could not delete {}", path, e)
				}
				return FileVisitResult.CONTINUE
			}
		}
	try {
		Files.walkFileTree(root, visitor)
	} catch (e: IOException) {
		log.debug("Could not walk {}", root, e)
	}
	return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
}
