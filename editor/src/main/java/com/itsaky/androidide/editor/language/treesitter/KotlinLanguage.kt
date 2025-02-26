package com.itsaky.androidide.editor.language.treesitter

import AndroidKotlinLanguageServer
import android.content.Context
import com.itsaky.androidide.editor.language.newline.TSBracketsHandler
import com.itsaky.androidide.editor.language.newline.TSCStyleBracketsHandler
import com.itsaky.androidide.editor.language.treesitter.TreeSitterLanguage.Factory
import com.itsaky.androidide.editor.language.utils.CommonSymbolPairs
import com.itsaky.androidide.lsp.api.ILanguageServer
import com.itsaky.androidide.lsp.api.ILanguageServerRegistry
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import io.github.rosemoe.sora.lang.Language.INTERRUPTION_LEVEL_STRONG
import io.github.rosemoe.sora.util.MyCharacter
import io.github.rosemoe.sora.widget.SymbolPairMatch

/**
 * Tree Sitter language specification for Kotlin.
 *
 * Extends the **working** version of `KotlinLanguage` while adding **LSP support**.
 *
 * @author Akash Yadav
 * @edited Vladimir
 */
open class KotlinLanguage(context: Context) :
  TreeSitterLanguage(context, TSLanguageKotlin.getInstance(), TS_TYPE_KT) {

  companion object {
    val FACTORY = Factory { KotlinLanguage(it) }
    const val TS_TYPE_KT = "kt"
    const val TS_TYPE_KTS = "kts"
  }

  override fun getInterruptionLevel(): Int {
    return INTERRUPTION_LEVEL_STRONG
  }

  override val languageServer: ILanguageServer?
    get() = ILanguageServerRegistry.getDefault().getServer(AndroidKotlinLanguageServer.SERVER_ID)

  override fun checkIsCompletionChar(c: Char): Boolean {
    return MyCharacter.isJavaIdentifierPart(c) || c == '.' || c == ':'
  }

  override fun getSymbolPairs(): SymbolPairMatch {
    return KotlinSymbolPairs()
  }

  override fun createNewlineHandlers(): Array<TSBracketsHandler> {
    return arrayOf(TSCStyleBracketsHandler(this))
  }

  internal open class KotlinSymbolPairs : CommonSymbolPairs() {
    init {
      super.putPair('(', SymbolPair("(", ")"))
      super.putPair('{', SymbolPair("{", "}"))
      super.putPair('[', SymbolPair("[", "]"))
      super.putPair('<', SymbolPair("<", ">"))
      super.putPair('"', SymbolPair("\"", "\""))
      super.putPair('\'', SymbolPair("'", "'"))
    }
  }
}
