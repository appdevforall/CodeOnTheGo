package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinTypeParameterCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        // Ensure we're inside a type parameter context (e.g., `<T`, `class MyClass<T>`, `fun <T>`)
        val isTypeParameterContext = fileContent.substring(0, cursorPosition).contains("<")
        if (!isTypeParameterContext) return emptyList()

        val typeParameters = extractTypeParameters(fileContent)
        val matchingTypeParameters = typeParameters.filter { it.startsWith(prefix) }

        return matchingTypeParameters.map { typeParameter ->
            CompletionItem(
                ideLabel = typeParameter,
                detail = "Type Parameter",
                insertText = typeParameter,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = typeParameter,
                command = null,
                completionKind = CompletionItemKind.TYPE_PARAMETER,
                matchLevel = CompletionItem.matchLevel(typeParameter, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractTypeParameters(sourceCode: String): List<String> {
        val regex = Regex("""<\s*([A-Za-z_][A-Za-z0-9_]*)(\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*\s*>""")
        val typeParameters = mutableListOf<String>()

        regex.findAll(sourceCode).forEach { matchResult ->
            val params = matchResult.value.removeSurrounding("<", ">")
                .split(",")
                .map { it.trim() }
            typeParameters.addAll(params)
        }

        return typeParameters
    }
}
