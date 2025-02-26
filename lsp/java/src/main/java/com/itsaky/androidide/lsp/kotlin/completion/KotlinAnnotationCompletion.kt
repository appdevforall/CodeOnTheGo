package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinAnnotationCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        // Ensure we are inside an annotation (@ symbol present before cursor)
        val isAnnotationContext = fileContent.substring(0, cursorPosition).contains("@")
        if (!isAnnotationContext) return emptyList()

        val annotations = extractAnnotations(fileContent)
        val matchingAnnotations = annotations.filter { it.startsWith(prefix) }

        return matchingAnnotations.map { annotationName ->
            CompletionItem(
                ideLabel = annotationName,
                detail = "Annotation",
                insertText = annotationName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = annotationName,
                command = null,
                completionKind = CompletionItemKind.ANNOTATION_TYPE,
                matchLevel = CompletionItem.matchLevel(annotationName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun extractAnnotations(sourceCode: String): List<String> {
        val regex = Regex("""\bannotation\s+class\s+([a-zA-Z_][a-zA-Z0-9_]*)""")
        val userDefinedAnnotations = regex.findAll(sourceCode).map { it.groupValues[1] }.toList()

        val kotlinAnnotations = listOf(
            "Deprecated", "JvmStatic", "JvmOverloads", "JvmField", "Throws",
            "JvmName", "JvmSuppressWildcards", "JvmDefault", "JvmRecord",
            "Serializable", "JvmInline"
        )

        return userDefinedAnnotations + kotlinAnnotations
    }
}
