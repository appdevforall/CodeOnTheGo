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
	 * Extracts [zipFile] into [destDir], preserving directory structure, and returns the list of
	 * files it wrote.
	 *
	 * Two kinds of entry do not appear in that list. An entry that would land outside [destDir]
	 * (zip-slip) fails the whole call with an [IOException] -- containment is checked before
	 * anything else, so a malicious entry cannot be quietly turned into a skip by the rule below.
	 * An entry whose target is an existing symlink is skipped and extraction continues: the target
	 * is already proven contained by then, and this keeps a user's own symlink (a `gradlew`, an SDK
	 * link) from being overwritten by an archive.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun unzipFile(
		zipFile: File,
		destDir: File,
	): List<File> {
		destDir.mkdirs()
		val contained = ContainedPathResolver(destDir)
		val result = mutableListOf<File>()

		ZipFile(zipFile).use { zip ->
			val entries = zip.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()

				// Containment first, then policy. The order used to be reversed, which meant
				// Files.isSymbolicLink ran on an unnormalized File(destDir, entry.name): for an entry
				// like ../../etc/x the kernel resolved the .. segments, the stat landed on a path
				// outside destDir, and if that happened to be a symlink the entry was skipped -- a
				// zip-slip attempt discarded quietly, where the same entry naming a regular file
				// correctly threw. Resolving first means the link check only ever sees a path already
				// proven to be inside destDir (ADFA-5257).
				val outFile =
					try {
						contained.resolve(entry.name)
					} catch (e: InvalidPathException) {
						// A name the platform cannot represent (an embedded NUL). Unchecked, and it
						// would otherwise escape this function's declared IOException contract.
						throw IOException("Zip entry has an unusable path: ${entry.name}", e)
					} ?: throw IOException("Zip entry escapes the target directory: ${entry.name}")

				// Policy, not containment: a user's own symlink inside their own project -- gradlew, or
				// gradle/wrapper pointed at a shared location -- is legitimate, so the entry is skipped
				// and their symlink left alone rather than written through. Logged, because the caller
				// is told the archive extracted and would otherwise have no way to know an entry did
				// not.
				if (Files.isSymbolicLink(outFile.toPath())) {
					log.info("Leaving the existing symlink at {} alone; that zip entry was not extracted", outFile)
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

				result.add(outFile)
			}
		}

		return result
	}
}
