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

import com.itsaky.androidide.app.BaseApplication
import org.slf4j.LoggerFactory
import java.io.File

object ResourceUtils {
	private val logger = LoggerFactory.getLogger(ResourceUtils::class.java)

	/**
	 * Copies the asset at [assetPath] to [destPath]. If [assetPath] names a directory (i.e. it has
	 * listable children), copies it recursively.
	 */
	@JvmStatic
	fun copyFileFromAssets(
		assetPath: String,
		destPath: String,
	): Boolean {
		val assets = BaseApplication.baseInstance.assets
		return try {
			val children = assets.list(assetPath)
			if (!children.isNullOrEmpty()) {
				var result = true
				for (child in children) {
					result = copyFileFromAssets("$assetPath/$child", "$destPath/$child") && result
				}
				result
			} else {
				val destFile = File(destPath)
				destFile.parentFile?.mkdirs()
				assets.open(assetPath).use { input ->
					destFile.outputStream().use { output -> input.copyTo(output) }
				}
				true
			}
		} catch (e: Exception) {
			logger.warn("Failed to copy asset '{}' to '{}'", assetPath, destPath, e)
			false
		}
	}

	/**
	 * Reads the asset at [assetPath] fully as a UTF-8 string, or an empty string if it can't be read.
	 */
	@JvmStatic
	fun readAssets2String(assetPath: String): String =
		try {
			BaseApplication.baseInstance.assets
				.open(assetPath)
				.use { it.readBytes().toString(Charsets.UTF_8) }
		} catch (e: Exception) {
			logger.warn("Failed to read asset '{}'", assetPath, e)
			""
		}
}
