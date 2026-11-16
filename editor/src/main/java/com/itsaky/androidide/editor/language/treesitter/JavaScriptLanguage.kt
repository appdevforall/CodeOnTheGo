package com.itsaky.androidide.editor.language.treesitter

import android.content.Context
import com.itsaky.androidide.editor.language.newline.TSBracketsHandler
import com.itsaky.androidide.treesitter.javascript.TSLanguageJavascript

/**
 * [TreeSitterLanguage] for JavaScript: highlighting, brackets, blocks and indentation. There is
 * no JavaScript language server.
 */
class JavaScriptLanguage : TreeSitterLanguage {
	constructor(context: Context) : super(context, TSLanguageJavascript.getInstance(), TS_TYPE)

	// For a test: building the spec from a Context reads the color scheme that the app's
	// preferences name (newLocalCaptureSpec), and a test has no preferences.
	internal constructor(spec: TreeSitterLanguageSpec) : super(TS_TYPE, spec)

	override fun createNewlineHandlers(): Array<TSBracketsHandler> = arrayOf(BracketsHandler(this))

	/**
	 * Enter between a pair of brackets puts the closing one on a line of its own, as
	 * TSCStyleBracketsHandler does for { } and ( ), and for [ ] too: array literals are common in
	 * JavaScript. Otherwise the ] stays at the start of the new line, and indents.scm dedents it.
	 */
	private class BracketsHandler(
		language: TreeSitterLanguage,
	) : TSBracketsHandler(language) {
		init {
			openingBrackets += listOf("{", "(", "[")
			closingBrackets += listOf("}", ")", "]")
		}
	}

	companion object {
		/** Names the query directory (`editor/treesitter/js`) and the color-scheme type. */
		const val TS_TYPE = "js"

		/** The file extensions opened as JavaScript. */
		val FILE_TYPES = listOf("js", "mjs", "cjs", "jsx")

		@JvmField
		val FACTORY = Factory { JavaScriptLanguage(it) }
	}
}
