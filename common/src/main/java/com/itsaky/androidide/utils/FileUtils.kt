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
import java.io.FileFilter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object FileUtils {
	@JvmStatic
	fun isUtf8(file: File): Boolean {
		if (!file.isFile) {
			return false
		}

		val decoder = Charsets.UTF_8.newDecoder()
		decoder.onMalformedInput(CodingErrorAction.REPORT)
		decoder.onUnmappableCharacter(CodingErrorAction.REPORT)

		return try {
			file.inputStream().use { input -> decoder.decode(ByteBuffer.wrap(input.readBytes())) }
			true
		} catch (e: Exception) {
			false
		}
	}

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

	@JvmStatic
	fun delete(file: File): Boolean = file.deleteRecursively()

	@JvmStatic
	fun delete(path: String): Boolean = delete(File(path))

	@JvmStatic
	fun rename(
		file: File,
		newName: String,
	): Boolean = file.renameTo(File(file.parentFile, newName))

	@JvmStatic
	fun getFileExtension(file: File): String = file.extension

	@JvmStatic
	fun createOrExistsDir(file: File): Boolean = file.isDirectory || file.mkdirs()
}

object FileIOUtils {
	@JvmStatic
	fun readFile2String(file: File): String = file.readText(Charsets.UTF_8)

	@JvmStatic
	fun writeFileFromString(
		file: File,
		content: String,
	): Boolean =
		try {
			file.parentFile?.mkdirs()
			file.writeText(content)
			true
		} catch (e: Exception) {
			false
		}
}
