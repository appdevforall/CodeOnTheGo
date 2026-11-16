package com.itsaky.androidide.editor.language.treesitter

import android.content.Context
import com.itsaky.androidide.editor.language.newline.TSBracketsHandler
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.treesitter.javascript.TSLanguageJavascript
import io.github.rosemoe.sora.util.MyCharacter

/**
 * [TreeSitterLanguage] for JavaScript: highlighting, brackets, blocks and indentation. The IDE
 * has no JavaScript language server; a plugin's server, when one is installed for the file type
 * ([serverId]), gives completion and diagnostics.
 */
class JavaScriptLanguage : TreeSitterLanguage {
	private val serverId: () -> String?

	constructor(context: Context, serverId: () -> String?) : super(context, TSLanguageJavascript.getInstance(), TS_TYPE) {
		this.serverId = serverId
	}

	// For a test: building the spec from a Context reads the color scheme that the app's
	// preferences name (newLocalCaptureSpec), and a test has no preferences.
	internal constructor(spec: TreeSitterLanguageSpec, serverId: () -> String? = { null }) : super(TS_TYPE, spec) {
		this.serverId = serverId
	}

	override val languageServer: ILanguageServer?
		get() = serverId()?.let { ILanguageServerRegistry.default.getServer(it) }

	override fun checkIsCompletionChar(c: Char): Boolean = MyCharacter.isJavaIdentifierPart(c) || c == '.'

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
		val FILE_TYPES = JavaScriptFileTypes.ALL

		/** [serverId] names the plugin's language server for the file type, or null for none. */
		@JvmStatic
		fun factory(serverId: () -> String?): Factory<JavaScriptLanguage> = Factory { JavaScriptLanguage(it, serverId) }
	}
}

/**
 * The file extensions opened as JavaScript. Apart from [JavaScriptLanguage] so that reading them
 * does not load the tree-sitter native library, which a JVM unit test cannot.
 */
object JavaScriptFileTypes {
	val ALL = listOf("js", "mjs", "cjs", "jsx")
}
