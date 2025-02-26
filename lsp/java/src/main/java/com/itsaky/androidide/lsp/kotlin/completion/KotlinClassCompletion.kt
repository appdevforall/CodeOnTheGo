package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinClassCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)
        val classNames = extractClasses(fileContent)

        val matchingClasses = classNames
            .sortedByDescending { it.first }
            .map { it.second }
            .filter { it.startsWith(prefix) }

        return matchingClasses.map { className ->
            CompletionItem(
                ideLabel = className,
                detail = "Class / Interface / Object",
                insertText = className,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = className,
                command = null,
                completionKind = CompletionItemKind.CLASS,
                matchLevel = CompletionItem.matchLevel(className, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractClasses(sourceCode: String): List<Pair<Int, String>> {
        val regex = Regex("""\b(data\s+class|sealed\s+class|class|interface|object)\s+([a-zA-Z_][a-zA-Z0-9_]*)""")
        return regex.findAll(sourceCode).map { it.range.first to it.groupValues[2] }.toList()
    }
}
