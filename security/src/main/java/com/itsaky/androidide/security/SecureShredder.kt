/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.security

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Random

/**
 * Best-effort overwrite-then-delete for Instant Wipe (ADFA-6510).
 *
 * Caveat: an overwrite-in-place is NOT a guaranteed physical erase on flash storage with a
 * flash-translation layer / wear-levelling, and even less on emulated external storage -- the new
 * bytes may land on different physical blocks. This raises the bar over a plain delete but must not
 * be described to users as a "secure erase". The real protections are deletion plus the app no longer
 * exposing the data (FLAG_SECURE, no backup). Reserve this routine for small, sensitive files
 * (source, the Recents DB); bulk regenerable caches are faster to plain-delete.
 */
object SecureShredder {
	private const val CHUNK_SIZE = 4096

	/**
	 * Overwrites [file]'s bytes with random data, fsyncs, truncates to zero, then deletes it.
	 * A non-existent file counts as already shredded. Never throws; returns whether the file is gone.
	 *
	 * @param random source of overwrite bytes; defaults to [SecureRandom]. Injectable for tests.
	 */
	fun shredFile(
		file: File,
		random: Random = SecureRandom(),
	): Boolean {
		if (!file.exists()) return true
		runCatching {
			RandomAccessFile(file, "rws").use { raf ->
				var remaining = raf.length()
				val buffer = ByteArray(CHUNK_SIZE)
				raf.seek(0)
				while (remaining > 0) {
					val n = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
					random.nextBytes(buffer)
					raf.write(buffer, 0, n)
					remaining -= n
				}
				raf.fd.sync()
				raf.setLength(0)
			}
		}
		// Delete regardless: even if the overwrite failed partway, removal is still the priority.
		return file.delete() || !file.exists()
	}

	/**
	 * Depth-first shred of a file or directory tree: shreds every file, then removes the emptied
	 * directories. Returns the number of files shredded (directories are not counted).
	 *
	 * A directory whose name is in [skipDirNames] is **fast plain-deleted** instead of shredded -- for
	 * regenerable caches (build output, Gradle caches) that hold no user secrets, so overwriting them
	 * would only cost time and defeat an "instant" wipe.
	 */
	fun shredTree(
		root: File,
		random: Random = SecureRandom(),
		skipDirNames: Set<String> = emptySet(),
	): Int {
		// A link is removed, never followed: what it points to may be outside the wipe, and shredding
		// through it would overwrite that.
		if (Files.isSymbolicLink(root.toPath())) {
			root.delete()
			return 0
		}
		if (!root.exists()) return 0
		var count = 0
		if (root.isDirectory) {
			if (root.name in skipDirNames) {
				deleteTree(root)
				return 0
			}
			root.listFiles()?.forEach { count += shredTree(it, random, skipDirNames) }
			root.delete()
		} else {
			if (shredFile(root, random)) count++
		}
		return count
	}

	/**
	 * Plain-deletes [root] and everything under it without following a symbolic link: a link is
	 * removed, not what it points to, which may be outside the wipe. A build can leave links in its
	 * output (ADFA-4930), and `File.deleteRecursively` follows a link to a folder. Never throws.
	 *
	 * @return whether nothing is left at [root].
	 */
	fun deleteTree(root: File): Boolean {
		val path = root.toPath()
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
		runCatching {
			Files.walkFileTree(
				path,
				object : SimpleFileVisitor<Path>() {
					override fun visitFile(
						file: Path,
						attrs: BasicFileAttributes,
					): FileVisitResult = deleteAndContinue(file)

					override fun visitFileFailed(
						file: Path,
						exc: IOException,
					): FileVisitResult = deleteAndContinue(file)

					override fun postVisitDirectory(
						dir: Path,
						exc: IOException?,
					): FileVisitResult = deleteAndContinue(dir)
				},
			)
		}
		return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
	}

	private fun deleteAndContinue(path: Path): FileVisitResult {
		runCatching { Files.deleteIfExists(path) }
		return FileVisitResult.CONTINUE
	}
}
