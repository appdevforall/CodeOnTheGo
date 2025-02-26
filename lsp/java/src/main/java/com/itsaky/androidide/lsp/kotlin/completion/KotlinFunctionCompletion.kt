package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinFunctionCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)
        val functions = extractFunctions(fileContent)
        val matchingFunctions = functions.filter { it.startsWith(prefix) }
        return matchingFunctions.map { functionName ->
            CompletionItem(
                ideLabel = functionName,
                detail = "Function",
                insertText = "$functionName()",
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = functionName,
                command = null,
                completionKind = CompletionItemKind.FUNCTION,
                matchLevel = CompletionItem.matchLevel(functionName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractFunctions(sourceCode: String): List<String> {
        val regex = Regex("""fun\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""") // Matches function names
        return regex.findAll(sourceCode).map { it.groupValues[1] }.toList()
    }
}