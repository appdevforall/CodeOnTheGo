/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.kotlin.adapters

import android.util.Log
import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.lsp.models.InsertTextFormat
import com.itsaky.androidide.lsp.models.MarkupContent
import com.itsaky.androidide.lsp.models.MarkupKind
import com.itsaky.androidide.lsp.models.MatchLevel
import com.itsaky.androidide.lsp.models.ParameterInformation
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureInformation
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import me.astrocoder.ktlsp.semantic.Diagnostic
import java.net.URI
import java.nio.file.Paths
import org.eclipse.lsp4j.CompletionItemKind as Lsp4jCompletionItemKind
import org.eclipse.lsp4j.DiagnosticSeverity as Lsp4jDiagnosticSeverity
import org.eclipse.lsp4j.InsertTextFormat as Lsp4jInsertTextFormat
import org.eclipse.lsp4j.Location as Lsp4jLocation
import org.eclipse.lsp4j.MarkupKind as Lsp4jMarkupKind
import org.eclipse.lsp4j.Position as Lsp4jPosition
import org.eclipse.lsp4j.Range as Lsp4jRange
import org.eclipse.lsp4j.SignatureHelp as Lsp4jSignatureHelp
import org.eclipse.lsp4j.CompletionItem as Lsp4jCompletionItem

fun Position.toLsp4j(): Lsp4jPosition = Lsp4jPosition(line, column)

fun Lsp4jPosition.toIde(): Position = Position(line, character)

fun Range.toLsp4j(): Lsp4jRange = Lsp4jRange(start.toLsp4j(), end.toLsp4j())

fun Lsp4jRange.toIde(): Range = Range(start.toIde(), end.toIde())

fun Lsp4jLocation.toIde(): Location {
    val path = try {
        Paths.get(URI(uri))
    } catch (e: Exception) {
        Paths.get(uri)
    }
    return Location(path, range.toIde())
}

fun Lsp4jCompletionItem.toIde(prefix: String): CompletionItem {
    val matchLevel = CompletionItem.matchLevel(label, prefix)

    return CompletionItem(
        label,
        detail ?: "",
        insertText,
        insertTextFormat?.toIde(),
        sortText,
        null,
        kind?.toIde() ?: CompletionItemKind.NONE,
        matchLevel,
        null,
        null
    )
}

fun Lsp4jCompletionItemKind.toIde(): CompletionItemKind {
    return when (this) {
        Lsp4jCompletionItemKind.Text -> CompletionItemKind.NONE
        Lsp4jCompletionItemKind.Method -> CompletionItemKind.METHOD
        Lsp4jCompletionItemKind.Function -> CompletionItemKind.FUNCTION
        Lsp4jCompletionItemKind.Constructor -> CompletionItemKind.CONSTRUCTOR
        Lsp4jCompletionItemKind.Field -> CompletionItemKind.FIELD
        Lsp4jCompletionItemKind.Variable -> CompletionItemKind.VARIABLE
        Lsp4jCompletionItemKind.Class -> CompletionItemKind.CLASS
        Lsp4jCompletionItemKind.Interface -> CompletionItemKind.INTERFACE
        Lsp4jCompletionItemKind.Module -> CompletionItemKind.MODULE
        Lsp4jCompletionItemKind.Property -> CompletionItemKind.PROPERTY
        Lsp4jCompletionItemKind.Keyword -> CompletionItemKind.KEYWORD
        Lsp4jCompletionItemKind.Snippet -> CompletionItemKind.SNIPPET
        Lsp4jCompletionItemKind.Value -> CompletionItemKind.VALUE
        Lsp4jCompletionItemKind.EnumMember -> CompletionItemKind.ENUM_MEMBER
        Lsp4jCompletionItemKind.Enum -> CompletionItemKind.ENUM
        Lsp4jCompletionItemKind.TypeParameter -> CompletionItemKind.TYPE_PARAMETER
        else -> CompletionItemKind.NONE
    }
}

fun Lsp4jInsertTextFormat.toIde(): InsertTextFormat {
    return when (this) {
        Lsp4jInsertTextFormat.PlainText -> InsertTextFormat.PLAIN_TEXT
        Lsp4jInsertTextFormat.Snippet -> InsertTextFormat.SNIPPET
    }
}

fun Diagnostic.toIde(positionToOffset: (line: Int, column: Int) -> Int): DiagnosticItem {
    val startIndex = if (range.hasOffsets) {
        range.startOffset
    } else {
        positionToOffset(range.startLine, range.startColumn)
    }
    val endIndex = if (range.hasOffsets) {
        range.endOffset
    } else {
        positionToOffset(range.endLine, range.endColumn)
    }

    Log.i("DIAG-DEBUG", "range=${range.startLine}:${range.startColumn}-${range.endLine}:${range.endColumn} -> idx=$startIndex-$endIndex (hasOffsets=${range.hasOffsets}) '${message.take(50)}'")

    return DiagnosticItem(
        message,
        code.name,
        Range(
            Position(range.startLine, range.startColumn, startIndex),
            Position(range.endLine, range.endColumn, endIndex)
        ),
        "ktlsp",
        severity.toIde()
    )
}

fun me.astrocoder.ktlsp.semantic.DiagnosticSeverity.toIde(): DiagnosticSeverity {
    return when (this) {
        me.astrocoder.ktlsp.semantic.DiagnosticSeverity.ERROR -> DiagnosticSeverity.ERROR
        me.astrocoder.ktlsp.semantic.DiagnosticSeverity.WARNING -> DiagnosticSeverity.WARNING
        me.astrocoder.ktlsp.semantic.DiagnosticSeverity.INFO -> DiagnosticSeverity.INFO
        me.astrocoder.ktlsp.semantic.DiagnosticSeverity.HINT -> DiagnosticSeverity.HINT
    }
}

fun Lsp4jSignatureHelp.toIde(): SignatureHelp {
    return SignatureHelp(
        signatures.map { it.toIde() },
        activeSignature ?: -1,
        activeParameter ?: -1
    )
}

fun org.eclipse.lsp4j.SignatureInformation.toIde(): SignatureInformation {
    val doc = when {
        documentation?.isRight == true -> {
            val markup = documentation.right
            MarkupContent(
                markup.value,
                when (markup.kind) {
                    Lsp4jMarkupKind.MARKDOWN -> MarkupKind.MARKDOWN
                    else -> MarkupKind.PLAIN
                }
            )
        }
        documentation?.isLeft == true -> MarkupContent(documentation.left, MarkupKind.PLAIN)
        else -> MarkupContent()
    }

    return SignatureInformation(
        label,
        doc,
        parameters?.map { it.toIde() } ?: emptyList()
    )
}

fun org.eclipse.lsp4j.ParameterInformation.toIde(): ParameterInformation {
    val labelStr = if (label.isLeft) label.left else "${label.right.first}-${label.right.second}"
    val doc = when {
        documentation?.isRight == true -> {
            val markup = documentation.right
            MarkupContent(
                markup.value,
                when (markup.kind) {
                    Lsp4jMarkupKind.MARKDOWN -> MarkupKind.MARKDOWN
                    else -> MarkupKind.PLAIN
                }
            )
        }
        documentation?.isLeft == true -> MarkupContent(documentation.left, MarkupKind.PLAIN)
        else -> MarkupContent()
    }

    return ParameterInformation(labelStr, doc)
}
