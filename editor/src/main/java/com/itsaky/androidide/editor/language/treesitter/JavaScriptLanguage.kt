package com.itsaky.androidide.editor.language.treesitter

import android.content.Context
import com.itsaky.androidide.editor.language.newline.TSBracketsHandler
import com.itsaky.androidide.editor.schemes.LanguageSpecProvider.getLanguageSpec
import com.itsaky.androidide.editor.schemes.LocalCaptureSpecProvider.newLocalCaptureSpec
import com.itsaky.androidide.treesitter.javascript.TSLanguageJavascript

/**
 * [TreeSitterLanguage] for JavaScript: highlighting, brackets, blocks and indentation. There is
 * no JavaScript language server.
 */
class JavaScriptLanguage internal constructor(
	spec: TreeSitterLanguageSpec,
) : TreeSitterLanguage(TS_TYPE, spec) {
	// The spec's local captures take their colors from the app's color scheme preference, so a
	// test, which has no preferences, passes its own spec to the constructor above.
	constructor(context: Context) : this(
		getLanguageSpec(context, TS_TYPE, TSLanguageJavascript.getInstance(), newLocalCaptureSpec(TS_TYPE)),
	)

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
