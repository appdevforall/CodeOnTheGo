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

package org.appdevforall.codeonthego.utils

import android.content.Context
import org.appdevforall.codeonthego.app.IDEApplication
import org.appdevforall.codeonthego.managers.ToolsManager
import org.appdevforall.codeonthego.templates.RecipeExecutor
import org.adfa.constants.LOCAL_MAVEN_CACHES_DEST
import org.adfa.constants.LOCAL_MAVEN_REPO_ARCHIVE_ZIP_NAME
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * [RecipeExecutor] implementation used for creating projects.
 *
 * @author Akash Yadav
 */
class TemplateRecipeExecutor(
	override val context: Context,
) : RecipeExecutor {
	private val application: IDEApplication
		get() = IDEApplication.instance

	override fun copy(
		source: File,
		dest: File,
	) {
		source.copyTo(dest)
	}

	override fun save(
		source: String,
		dest: File,
	) {
		dest.parentFile?.mkdirs()
		dest.writeText(source)
	}

	override fun openAsset(path: String): InputStream {
		try {
			return application.assets.open(path)
		} catch (e: Exception) {
			throw RuntimeException(e)
		}
	}

	override fun copyAsset(
		path: String,
		dest: File,
	) {
		openAsset(path).use { input ->
			dest.outputStream().use { output ->
				input.copyTo(output)
			}
		}
	}

	override fun copyAssetsRecursively(
		path: String,
		destDir: File,
	) {
		ResourceUtils.copyFileFromAssets(path, destDir.absolutePath)
	}
}
