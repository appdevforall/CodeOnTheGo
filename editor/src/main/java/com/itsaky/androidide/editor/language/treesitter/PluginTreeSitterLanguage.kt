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
import io.github.rosemoe.sora.util.MyCharacter
import java.io.File

class PluginTreeSitterLanguage(
	langType: String,
	languageSpec: TreeSitterLanguageSpec,
	private val languageServerId: String?,
) : TreeSitterLanguage(langType, languageSpec) {
	override val languageServer: ILanguageServer?
		get() = languageServerId?.let { ILanguageServerRegistry.default.getServer(it) }

	override fun checkIsCompletionChar(c: Char): Boolean = MyCharacter.isJavaIdentifierPart(c) || c == '.'

	override fun createNewlineHandlers(): Array<TSBracketsHandler> = arrayOf(TSCStyleBracketsHandler(this))

	class Factory(
		private val langType: String,
		private val grammarLibrary: File,
		private val grammarName: String,
		private val queriesAssets: AssetManager,
		private val queriesDir: String,
		private val languageServerId: String?,
	) : TreeSitterLanguage.Factory<PluginTreeSitterLanguage> {
		override fun create(context: Context): PluginTreeSitterLanguage {
			val grammar =
				TSLanguage.loadLanguage(grammarLibrary.absolutePath, grammarName)
					?: throw IllegalStateException(
						"Unable to load tree_sitter_$grammarName from ${grammarLibrary.absolutePath}",
					)
			val spec =
				LanguageSpecProvider.getLanguageSpec(
					queriesAssets,
					queriesDir,
					grammar,
					newLocalCaptureSpec(langType),
				)
			return PluginTreeSitterLanguage(langType, spec, languageServerId)
		}
	}
}
