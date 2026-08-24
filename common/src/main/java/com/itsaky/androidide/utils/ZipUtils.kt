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
import java.nio.file.InvalidPathException
import java.util.zip.ZipFile

object ZipUtils {
	/**
	 * Extracts every entry of [zipFile] into [destDir], preserving directory structure, and
	 * returns the list of extracted files. Rejects entries that would extract outside [destDir]
	 * (zip-slip).
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

				// Policy before containment, deliberately: a user's own symlink inside their project
				// -- gradlew, or gradle/wrapper pointed at a shared location -- is legitimate, so the
				// entry is skipped and the symlink left alone, where asking the resolver first would
				// reject one pointing outside destDir and abort the whole archive. Reading a path's
				// link status cannot itself escape.
				// toPath() throws InvalidPathException for a name the platform cannot represent (an
				// embedded NUL, say) -- an unchecked exception that would escape unzipFile's declared
				// IOException contract before the resolver ever saw the entry.
				val target =
					try {
						File(destDir, entry.name).toPath()
					} catch (e: InvalidPathException) {
						throw IOException("Zip entry has an unusable path: ${entry.name}", e)
					}
				if (Files.isSymbolicLink(target)) {
					continue
				}

				// Containment is ContainedPathResolver's, shared with the asset installer: a canonical
				// path prefix alone accepted a "..", and could not tell a symlinked ancestor from a
				// real directory (ADFA-5257).
				val outFile =
					contained.resolve(entry.name)
						?: throw IOException("Zip entry escapes the target directory: ${entry.name}")

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
