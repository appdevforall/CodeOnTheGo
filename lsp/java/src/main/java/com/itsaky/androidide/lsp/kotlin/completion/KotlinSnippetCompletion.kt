package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinSnippetCompletion : KotlinCompletion {
    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        return listOf(
            createSnippet("main", "Main Function", "fun main() {\n    println(\"Hello, World!\")\n}", "fun main() { }"),
            createSnippet("print", "Print Statement", "println(\"\")", "println()"),
            createSnippet("if", "If Statement", "if (condition) {\n    // Code\n} else {\n    // Code\n}", "if (condition) { }"),
            createSnippet("for", "For Loop", "for (i in 0 until 10) {\n    // Loop body\n}", "for (i in 0 until 10) { }"),
            createSnippet("when", "When Expression", "when (value) {\n    else -> {}\n}", "when (value) { }")
        )
    }

    private fun createSnippet(label: String, detail: String, code: String, sortText: String): CompletionItem {
        return CompletionItem(
            ideLabel = label,
            detail = detail,
            insertText = code,
            insertTextFormat = InsertTextFormat.SNIPPET,
            sortText = sortText,
            command = null,
            completionKind = CompletionItemKind.SNIPPET,
            matchLevel = com.itsaky.androidide.lsp.models.MatchLevel.CASE_INSENSITIVE_PREFIX,
            additionalTextEdits = null,
            data = null
        )
    }
}
