package com.itsaky.androidide.editor.language.treesitter

import android.content.Context
import android.content.res.AssetManager
import com.itsaky.androidide.editor.language.newline.TSBracketsHandler
import com.itsaky.androidide.editor.language.newline.TSCStyleBracketsHandler
import com.itsaky.androidide.editor.schemes.LanguageSpecProvider
import com.itsaky.androidide.editor.schemes.LocalCaptureSpecProvider.newLocalCaptureSpec
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSLanguageCache
import io.github.rosemoe.sora.util.MyCharacter
import java.io.File
import java.util.IdentityHashMap

class PluginTreeSitterLanguage(
	langType: String,
	languageSpec: TreeSitterLanguageSpec,
	private val grammar: TSLanguage,
	private val languageServerId: String?,
) : TreeSitterLanguage(langType, languageSpec) {
	private var destroyed = false

	override val languageServer: ILanguageServer?
		get() = languageServerId?.let { ILanguageServerRegistry.default.getServer(it) }

	override fun checkIsCompletionChar(c: Char): Boolean = MyCharacter.isJavaIdentifierPart(c) || c == '.'

	override fun createNewlineHandlers(): Array<TSBracketsHandler> = arrayOf(TSCStyleBracketsHandler(this))

	override fun destroy() {
		if (destroyed) return
		destroyed = true
		super.destroy()
		PluginGrammars.release(grammar)
	}

	class Factory(
		private val langType: String,
		private val grammarLibrary: File,
		private val grammarName: String,
		private val queriesAssets: AssetManager,
		private val queriesDir: String,
		private val languageServerId: String?,
	) : TreeSitterLanguage.Factory<PluginTreeSitterLanguage> {
		override fun create(context: Context): PluginTreeSitterLanguage {
			val grammar = PluginGrammars.acquire(grammarLibrary, grammarName)
			val spec =
				try {
					LanguageSpecProvider.getLanguageSpec(
						queriesAssets,
						queriesDir,
						grammar,
						newLocalCaptureSpec(langType),
					)
				} catch (e: IllegalArgumentException) {
					PluginGrammars.release(grammar)
					throw GrammarLoadException("Invalid tree-sitter queries for $grammarName in $queriesDir", e)
				}
			return PluginTreeSitterLanguage(langType, spec, grammar, languageServerId)
		}
	}

	class GrammarLoadException(
		message: String,
		cause: Throwable? = null,
	) : RuntimeException(message, cause)
}

object PluginGrammars {
	private val users = IdentityHashMap<TSLanguage, Int>()
	private val retired = mutableSetOf<String>()

	@Synchronized
	fun acquire(
		library: File,
		name: String,
	): TSLanguage {
		val grammar =
			try {
				TSLanguage.loadLanguage(library.absolutePath, name)
			} catch (e: IllegalArgumentException) {
				throw PluginTreeSitterLanguage.GrammarLoadException("Invalid tree-sitter grammar name '$name'", e)
			} ?: throw PluginTreeSitterLanguage.GrammarLoadException(
				"Unable to load tree_sitter_$name from ${library.absolutePath}",
			)
		users[grammar] = (users[grammar] ?: 0) + 1
		return grammar
	}

	@Synchronized
	fun release(grammar: TSLanguage) {
		val remaining = (users[grammar] ?: return) - 1
		if (remaining > 0) {
			users[grammar] = remaining
			return
		}
		users.remove(grammar)
		if (grammar.name in retired) closeGrammar(grammar)
	}

	@Synchronized
	fun retire(name: String) {
		if (users.keys.any { it.name == name }) {
			retired += name
			return
		}
		TSLanguageCache.get(name)?.takeIf { it.isExternal }?.close()
	}

	private fun closeGrammar(grammar: TSLanguage) {
		retired.remove(grammar.name)
		grammar.close()
	}
}
