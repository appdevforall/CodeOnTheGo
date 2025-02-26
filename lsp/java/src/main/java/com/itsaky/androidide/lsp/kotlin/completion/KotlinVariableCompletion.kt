package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinVariableCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)
        val variables = extractVariables(fileContent)
        val matchingVariables = variables.filter { it.startsWith(prefix) }

        return matchingVariables.map { variableName ->
            CompletionItem(
                ideLabel = variableName,
                detail = "Variable",
                insertText = variableName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = variableName,
                command = null,
                completionKind = CompletionItemKind.VARIABLE,
                matchLevel = CompletionItem.matchLevel(variableName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractVariables(sourceCode: String): List<String> {
        val regex = Regex("""\b(val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)""")
        return regex.findAll(sourceCode).map { it.groupValues[2] }.toList()
    }
}