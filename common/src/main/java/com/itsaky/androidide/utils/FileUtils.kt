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
import java.io.FileFilter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

private const val UTF8_SNIFF_LENGTH = 24

/** File helpers not tied to reading/writing content (see [FileIOUtils] for that). */
object FileUtils {
	/**
	 * Sniffs whether [file] looks like valid UTF-8 by decoding only its first
	 * [UTF8_SNIFF_LENGTH] bytes; a large file with invalid bytes past that sample still passes.
	 *
	 * Decodes with `endOfInput = false` so a multi-byte sequence truncated at the sample
	 * boundary reports underflow (needs more bytes) rather than a malformed-input error -
	 * otherwise a genuinely valid UTF-8 file could fail this check purely because a
	 * multi-byte character happened to straddle byte [UTF8_SNIFF_LENGTH].
	 */
	@JvmStatic
	fun isUtf8(file: File): Boolean {
		if (!file.isFile) {
			return false
		}

		val decoder = Charsets.UTF_8.newDecoder()
		decoder.onMalformedInput(CodingErrorAction.REPORT)
		decoder.onUnmappableCharacter(CodingErrorAction.REPORT)

		return try {
			val header = ByteArray(UTF8_SNIFF_LENGTH)
			val read = file.inputStream().use { it.read(header) }
			if (read <= 0) {
				return false
			}
			val input = ByteBuffer.wrap(header, 0, read)
			val output = CharBuffer.allocate(read)
			!decoder.decode(input, output, false).isError
		} catch (e: IOException) {
			false
		}
	}

	/** Lists children of [dir] matching [filter], recursing into subdirectories if [recursive]. */
	@JvmStatic
	fun listFilesInDirWithFilter(
		dir: File,
		filter: FileFilter,
		recursive: Boolean,
	): List<File> {
		if (!dir.isDirectory) {
			return emptyList()
		}

		val files = dir.listFiles() ?: return emptyList()
		val result = mutableListOf<File>()
		for (file in files) {
			if (filter.accept(file)) {
				result.add(file)
			}
			if (recursive && file.isDirectory) {
				result.addAll(listFilesInDirWithFilter(file, filter, true))
			}
		}
		return result
	}

	/** Deletes [file], recursing into directories. Returns whether everything was removed. */
	@JvmStatic
	fun delete(file: File): Boolean = file.deleteRecursively()

	/** @see delete */
	@JvmStatic
	fun delete(path: String): Boolean = delete(File(path))

	/** Renames [file] to [newName] in the same directory; fails (returns `false`) if that name is already taken. */
	@JvmStatic
	fun rename(
		file: File,
		newName: String,
	): Boolean {
		val newFile = File(file.parentFile, newName)
		return !newFile.exists() && file.renameTo(newFile)
	}

	@JvmStatic
	fun getFileExtension(file: File): String = file.extension

	/** Ensures [file] exists as a directory, creating it (and parents) if needed. */
	@JvmStatic
	fun createOrExistsDir(file: File): Boolean = file.isDirectory || file.mkdirs()
}

/** Whole-file read/write helpers that return a failure value instead of throwing. */
object FileIOUtils {
	private val logger = LoggerFactory.getLogger(FileIOUtils::class.java)

	/** Reads all of [file] as UTF-8, or `null` if it doesn't exist or can't be read. */
	@JvmStatic
	fun readFile2String(file: File): String? =
		try {
			file.readText(Charsets.UTF_8)
		} catch (e: IOException) {
			null
		}

	/** Writes [content] to [file] (creating parent directories as needed). Returns whether it succeeded. */
	@JvmStatic
	fun writeFileFromString(
		file: File,
		content: String,
	): Boolean =
		try {
			file.parentFile?.mkdirs()
			file.writeText(content)
			true
		} catch (e: IOException) {
			logger.warn("Failed to write file: {}", file, e)
			false
		}
}
