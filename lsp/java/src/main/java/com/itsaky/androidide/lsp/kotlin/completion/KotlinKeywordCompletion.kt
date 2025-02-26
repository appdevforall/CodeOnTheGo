package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinKeywordCompletion : KotlinCompletion {

    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        val keywords = kotlinKeywords.filter { it.startsWith(prefix) }

        return keywords.map { keyword ->
            CompletionItem(
                ideLabel = keyword,
                detail = "Kotlin Keyword",
                insertText = keyword,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = keyword,
                command = null,
                completionKind = CompletionItemKind.KEYWORD,
                matchLevel = CompletionItem.matchLevel(keyword, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    companion object {
        private val kotlinKeywords = listOf(
            "as", "as?", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while",
            // Modifiers
            "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
            "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
            "open", "operator", "out", "override", "private", "protected", "public", "reified",
            "sealed", "suspend", "tailrec", "vararg"
        )
    }
}
