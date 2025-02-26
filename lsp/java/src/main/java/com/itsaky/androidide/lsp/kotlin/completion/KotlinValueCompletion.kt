package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinValueCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)
        val values = extractValues(fileContent)
        val matchingValues = values.filter { it.startsWith(prefix) }

        return matchingValues.map { valueName ->
            CompletionItem(
                ideLabel = valueName,
                detail = "Constant / Object Value",
                insertText = valueName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = valueName,
                command = null,
                completionKind = CompletionItemKind.VALUE,
                matchLevel = CompletionItem.matchLevel(valueName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractValues(sourceCode: String): List<String> {
        val regex = Regex("""\b(?:val|const val)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*=""")
        return regex.findAll(sourceCode).map { it.groupValues[1] }.toList()
    }
}
