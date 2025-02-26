package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat
import java.util.regex.Pattern

class KotlinModuleCompletion : KotlinCompletion {

    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        val isModuleContext = isInsideImportOrPackage(fileContent, cursorPosition)
        if (!isModuleContext) return emptyList()

        val modules = extractModules(fileContent)
        val matchingModules = modules.filter { it.startsWith(prefix) }

        return matchingModules.map { module ->
            CompletionItem(
                ideLabel = module,
                detail = "Module / Package",
                insertText = module,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = module,
                command = null,
                completionKind = CompletionItemKind.MODULE,
                matchLevel = CompletionItem.matchLevel(module, prefix),
                additionalTextEdits = null,
                data = null
            )
        }
    }

    private fun isInsideImportOrPackage(sourceCode: String, cursorIndex: Int): Boolean {
        val lines = sourceCode.substring(0, cursorIndex).split("\n")
        for (line in lines.reversed()) {
            if (line.trim().startsWith("import") || line.trim().startsWith("package")) {
                return true
            }
            if (line.isNotEmpty() && !line.startsWith("//")) {
                break
            }
        }
        return false
    }

    private fun extractModules(sourceCode: String): List<String> {
        val pattern = Pattern.compile("""\bimport\s+([a-zA-Z0-9_.]+)""")
        val modules = mutableSetOf<String>()

        val matcher = pattern.matcher(sourceCode)
        while (matcher.find()) {
            val importStatement = matcher.group(1)
            val parts = importStatement.split(".")
            for (i in parts.indices) {
                modules.add(parts.subList(0, i + 1).joinToString("."))
            }
        }

        modules.addAll(
            listOf(
                "kotlin", "kotlinx", "java", "javax", "android", "androidx", "com"
            )
        )

        return modules.toList()
    }
}
