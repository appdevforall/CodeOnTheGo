package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinMethodCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)
        val methods = extractMethods(fileContent)

        val matchingMethods = methods
            .sortedByDescending { it.first }
            .map { it.second }
            .filter { it.startsWith(prefix) }

        return matchingMethods.map { methodName ->
            CompletionItem(
                ideLabel = methodName,
                detail = "Method / Function",
                insertText = "$methodName()",
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = methodName,
                command = null,
                completionKind = CompletionItemKind.METHOD,
                matchLevel = CompletionItem.matchLevel(methodName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractMethods(sourceCode: String): List<Pair<Int, String>> {
        val regex = Regex("""\b(fun)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
        return regex.findAll(sourceCode).map { it.range.first to it.groupValues[2] }.toList()
    }
}
