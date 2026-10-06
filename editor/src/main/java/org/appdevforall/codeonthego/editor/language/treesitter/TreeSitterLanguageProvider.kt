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

package org.appdevforall.codeonthego.editor.language.treesitter

import android.content.Context
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Provides instance of [TreeSitterLanguage] implementations.
 *
 * @author Akash Yadav
 */
object TreeSitterLanguageProvider {
	private val log = LoggerFactory.getLogger(TreeSitterLanguageProvider::class.java)

	fun hasTsLanguage(file: File): Boolean = TSLanguageRegistry.instance.hasLanguage(file.extension)

	fun forFile(
		file: File,
		context: Context,
	): TreeSitterLanguage? {
		if (!hasTsLanguage(file)) {
			return null
		}

		return forType(file.extension, context)
	}

	fun forType(
		type: String,
		context: Context,
	): TreeSitterLanguage? =
		try {
			TSLanguageRegistry.instance.getFactory<TreeSitterLanguage>(type).create(context)
		} catch (e: TSLanguageRegistry.NotRegisteredException) {
			null
		} catch (e: PluginTreeSitterLanguage.GrammarLoadException) {
			log.error("Plugin grammar for '.{}' failed to load; opening without tree-sitter", type, e)
			null
		}
}
