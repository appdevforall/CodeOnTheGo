package com.itsaky.androidide.editor.language.treesitter

import android.content.Context
import com.itsaky.androidide.editor.language.newline.TSBracketsHandler
import com.itsaky.androidide.editor.language.newline.TSCStyleBracketsHandler
import com.itsaky.androidide.treesitter.javascript.TSLanguageJavascript

/**
 * [TreeSitterLanguage] for JavaScript: highlighting, brackets, blocks and indentation. There is
 * no JavaScript language server.
 */
class JavaScriptLanguage(
	context: Context,
) : TreeSitterLanguage(context, TSLanguageJavascript.getInstance(), TS_TYPE) {
	override fun createNewlineHandlers(): Array<TSBracketsHandler> = arrayOf(TSCStyleBracketsHandler(this))

	companion object {
		/** Names the query directory (`editor/treesitter/js`) and the color-scheme type. */
		const val TS_TYPE = "js"

		/** The file extensions opened as JavaScript. */
		val FILE_TYPES = listOf("js", "mjs", "cjs", "jsx")

		@JvmField
		val FACTORY = Factory { JavaScriptLanguage(it) }
	}
}
