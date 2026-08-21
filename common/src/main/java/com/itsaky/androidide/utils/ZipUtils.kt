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

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile

object ZipUtils {
	/**
	 * Extracts every entry of [zipFile] into [destDir], preserving directory structure, and
	 * returns the list of extracted files. Rejects entries that would extract outside [destDir]
	 * (zip-slip).
	 *
	 * Mirrors the containment checks in
	 * [com.itsaky.androidide.assets.AssetsInstallationHelper.extractZipToDir] and
	 * [com.itsaky.androidide.utils.resolveWithinDirectory] (a third, independent implementation of
	 * the same lexical-reject + normalize-and-verify + symlink-resolve pattern, needed here because
	 * this `common` module can't depend on `app`, which those two live in). Any future fix to the
	 * containment algorithm must be applied in all three places.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun unzipFile(
		zipFile: File,
		destDir: File,
	): List<File> {
		destDir.mkdirs()
		val destDirPath = destDir.canonicalPath + File.separator
		val result = mutableListOf<File>()

		ZipFile(zipFile).use { zip ->
			val entries = zip.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()

				// Per-segment, not a bare substring match: "notes..txt" or "a..b/c.txt" are harmless
				// names that a substring check would wrongly abort the whole archive over.
				if (entry.name.startsWith("/") || entry.name.startsWith("\\") ||
					entry.name.split('/', '\\').any { it == ".." }
				) {
					throw IOException("Zip entry contains dangerous path components: ${entry.name}")
				}

				val outFile = File(destDir, entry.name)

				if (!outFile.canonicalPath.startsWith(destDirPath)) {
					throw IOException("Zip entry is outside of the target directory: ${entry.name}")
				}

				// The checks above are lexical (entry name) or rely on canonicalPath's own symlink
				// resolution for a path that may not exist yet -- neither catches writing through an
				// existing symlink already inside destDir. A user symlinking e.g. gradlew or
				// gradle/wrapper to a shared location is legitimate, so skip this one entry (leaving
				// their symlink as-is) rather than aborting the whole extraction over it.
				if (Files.isSymbolicLink(outFile.toPath())) {
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
