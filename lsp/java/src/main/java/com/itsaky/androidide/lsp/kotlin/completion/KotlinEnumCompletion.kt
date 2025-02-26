package com.itsaky.androidide.lsp.kotlin.completion

import com.itsaky.androidide.lsp.kotlin.completion.CursorHelper.getPrefixBeforeCursor
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.InsertTextFormat

class KotlinEnumCompletion : KotlinCompletion {

    override suspend fun complete(fileContent: String, cursorPosition: Int): List<CompletionItem> {
        val prefix = getPrefixBeforeCursor(fileContent, cursorPosition)

        val enums = extractEnums(fileContent)
        val enumMembers = extractEnumMembers(fileContent)

        val matchingEnums = enums.filter { it.startsWith(prefix) }
        val matchingEnumMembers = enumMembers.filter { it.startsWith(prefix) }

        val enumCompletions = matchingEnums.map { enumName ->
            CompletionItem(
                ideLabel = enumName,
                detail = "Enum Class",
                insertText = enumName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = enumName,
                command = null,
                completionKind = CompletionItemKind.ENUM,
                matchLevel = CompletionItem.matchLevel(enumName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }

        val enumMemberCompletions = matchingEnumMembers.map { memberName ->
            CompletionItem(
                ideLabel = memberName,
                detail = "Enum Member",
                insertText = memberName,
                insertTextFormat = InsertTextFormat.PLAIN_TEXT,
                sortText = memberName,
                command = null,
                completionKind = CompletionItemKind.ENUM_MEMBER,
                matchLevel = CompletionItem.matchLevel(memberName, prefix),
                additionalTextEdits = null,
                data = null
            )
        }

        return enumCompletions + enumMemberCompletions
    }

    private fun extractEnums(sourceCode: String): List<String> {
        val regex = Regex("""\benum\s+class\s+([a-zA-Z_][a-zA-Z0-9_]*)""")
        return regex.findAll(sourceCode).map { it.groupValues[1] }.toList()
    }

    private fun extractEnumMembers(sourceCode: String): List<String> {
        val regex = Regex("""\benum\s+class\s+[a-zA-Z_][a-zA-Z0-9_]*\s*\{([^}]*)\}""")
        return regex.findAll(sourceCode)
            .flatMap { it.groupValues[1].split(",") }
            .map { it.trim().takeWhile { ch -> ch.isLetterOrDigit() || ch == '_' } }
            .filter { it.isNotEmpty() }.toList()
    }
}
