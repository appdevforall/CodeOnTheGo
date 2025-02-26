package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinPropertyFieldCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        val properties = extractProperties(fileContent)
        val fields = extractFields(fileContent)

        val matchingProperties = properties.filter { it.startsWith(prefix) }
        val matchingFields = fields.filter { it.startsWith(prefix) }

        val propertyCompletions = matchingProperties.map { propName ->
            CompletionItem(
                ideLabel = propName,
                detail = "Property",
                insertText = propName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = propName,
                command = null,
                completionKind = CompletionItemKind.PROPERTY,
                matchLevel = CompletionItem.matchLevel(propName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }

        val fieldCompletions = matchingFields.map { fieldName ->
            CompletionItem(
                ideLabel = fieldName,
                detail = "Field",
                insertText = fieldName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = fieldName,
                command = null,
                completionKind = CompletionItemKind.FIELD,
                matchLevel = CompletionItem.matchLevel(fieldName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }

        return propertyCompletions + fieldCompletions
    }

    private fun extractProperties(sourceCode: String): List<String> {
        val regex = Regex("""\b(val|var)\s+([a-zA-Z_][a-zA-Z0-9_]*)""")
        return regex.findAll(sourceCode).map { it.groupValues[2] }.toList()
    }

    private fun extractFields(sourceCode: String): List<String> {
        val regex = Regex("""\b(?:data\s+class\s+[a-zA-Z_][a-zA-Z0-9_]*\s*\((.*?)\))""")
        return regex.findAll(sourceCode).flatMap { match ->
            match.groupValues[1].split(",").map {
                it.trim().split(" ").last() // Extracts field name from `val name: String`
            }
        }.toList()
    }
}
