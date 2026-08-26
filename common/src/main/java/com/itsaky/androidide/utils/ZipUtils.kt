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

package com.itsaky.androidide.utils

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.util.zip.ZipFile

object ZipUtils {
	private val log = LoggerFactory.getLogger(ZipUtils::class.java)

	/**
	 * What [unzipFile] did with each entry: [extracted] holds the files it wrote, [skipped] the
	 * names of entries it did not extract because a symlink already sits at their target. A caller
	 * that needs specific entries on disk must check [skipped] (or the files themselves) rather
	 * than trusting a normal return.
	 */
	data class UnzipResult(
		val extracted: List<File>,
		val skipped: List<String>,
	)

	/**
	 * Extracts [zipFile] into [destDir], preserving directory structure, and returns an
	 * [UnzipResult] reporting the files it wrote and the entries it skipped.
	 *
	 * An entry whose target is an existing symlink -- live, dangling, or even pointing outside
	 * [destDir] -- is skipped and extraction continues: nothing is written at or through the link,
	 * which keeps a user's own symlink (a `gradlew`, an SDK link) from being overwritten by an
	 * archive. The skip applies only to a link lexically inside [destDir] whose entry name carries
	 * no traversal syntax; an entry that would land outside [destDir] (zip-slip), names its target
	 * through a `..` segment, or is not a representable path fails the whole call with an
	 * [IOException].
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun unzipFile(
		zipFile: File,
		destDir: File,
	): UnzipResult {
		destDir.mkdirs()
		val contained = ContainedPathResolver(destDir)
		val extracted = mutableListOf<File>()
		val skipped = mutableListOf<String>()

		ZipFile(zipFile).use { zip ->
			val entries = zip.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()

				// Containment first, then link policy: the link check must only ever see a path
				// already proven contained. Checking File(destDir, entry.name) before containment
				// would stat outside destDir for a ../ entry and could quietly skip a zip-slip
				// attempt (ADFA-5257).
				val outFile =
					contained.resolve(entry.name)
						?: if (isContainedSymlink(destDir, entry.name)) {
							// The resolver refuses a symlink it cannot verify -- dangling, or pointing
							// outside -- but a link that sits lexically inside destDir is the user's
							// own, and skipping writes nothing at or through it. Same policy as below.
							log.info("Leaving the existing symlink at {}/{} alone; that zip entry was not extracted", destDir, entry.name)
							skipped.add(entry.name)
							continue
						} else {
							throw IOException("Zip entry does not resolve to a safe path inside the target directory: ${entry.name}")
						}

				// Policy, not containment: a user's own symlink inside their own project -- gradlew, or
				// gradle/wrapper pointed at a shared location -- is legitimate, so the entry is skipped
				// and their symlink left alone rather than written through. Reported via the result,
				// because the caller is otherwise told the archive extracted.
				if (Files.isSymbolicLink(outFile.toPath())) {
					log.info("Leaving the existing symlink at {} alone; that zip entry was not extracted", outFile)
					skipped.add(entry.name)
					continue
				}

				if (entry.isDirectory) {
					outFile.mkdirs()
				} else {
					outFile.parentFile?.mkdirs()
					zip.getInputStream(entry).use { input ->
						outFile.outputStream().use { output -> input.copyTo(output) }
					}
				}

				extracted.add(outFile)
			}
		}

		return UnzipResult(extracted, skipped)
	}

	/**
	 * Whether a symlink exists at [entryName]'s lexically-normalized path inside [destDir]. Applies
	 * the resolver's own lexical reject first, so an entry the resolver refused for its *syntax* (a
	 * `..` segment, an absolute path) stays a bad archive even when a symlink happens to sit at its
	 * normalized target -- `a/../link.txt` must fail, not ride the skip meant for `link.txt`. Then
	 * every ancestor between [destDir] and the candidate must be a non-link: stat'ing the candidate
	 * *follows* an ancestor symlink, so with `a -> /outside`, `a/link.txt` would stat
	 * `/outside/link.txt` and a link found there would ride the skip -- an escaping archive
	 * tolerated instead of rejected. Only a link whose every ancestor is a real directory inside
	 * [destDir] qualifies, and the stats stay on paths proven lexically inside [destDir].
	 */
	private fun isContainedSymlink(
		destDir: File,
		entryName: String,
	): Boolean {
		if (ContainedPathResolver.isLexicallyRejected(entryName)) {
			return false
		}
		val base = destDir.toPath().toAbsolutePath().normalize()
		val candidate =
			try {
				base.resolve(entryName).normalize()
			} catch (_: InvalidPathException) {
				return false
			}
		if (candidate == base || !candidate.startsWith(base)) {
			return false
		}
		var ancestor = candidate.parent
		while (ancestor != null && ancestor != base) {
			if (Files.isSymbolicLink(ancestor)) {
				return false
			}
			ancestor = ancestor.parent
		}
		return Files.isSymbolicLink(candidate)
	}
}
