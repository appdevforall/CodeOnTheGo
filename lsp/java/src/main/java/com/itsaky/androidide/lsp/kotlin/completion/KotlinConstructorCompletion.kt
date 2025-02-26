package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinConstructorCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        val isConstructorContext = fileContent.substring(0, cursorPosition).contains("new ") ||
            fileContent.substring(0, cursorPosition).contains("=") ||
            fileContent.substring(0, cursorPosition).contains("return ")
        if (!isConstructorContext) return emptyList()

        val constructors = extractConstructors(fileContent)
        val matchingConstructors = constructors.filter { it.startsWith(prefix) }

        return matchingConstructors.map { constructorSignature ->
            CompletionItem(
                ideLabel = constructorSignature,
                detail = "Constructor",
                insertText = "$constructorSignature()",
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = constructorSignature,
                command = null,
                completionKind = CompletionItemKind.CONSTRUCTOR,
                matchLevel = CompletionItem.matchLevel(constructorSignature, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractConstructors(sourceCode: String): List<String> {
        val regex = Regex("""\bclass\s+([A-Za-z_][A-Za-z0-9_]*)\s*(\(([^)]*)\))?""")
        val constructors = mutableListOf<String>()

        regex.findAll(sourceCode).forEach { matchResult ->
            val className = matchResult.groupValues[1]
            val parameters = matchResult.groupValues[3]

            if (parameters.isNotEmpty()) {
                constructors.add("$className($parameters)")
            } else {
                constructors.add("$className()")
            }
        }

        return constructors
    }
}
