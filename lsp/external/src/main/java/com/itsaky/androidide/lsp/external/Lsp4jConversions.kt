package com.itsaky.androidide.lsp.external

import com.itsaky.androidide.lsp.models.CompletionItem
import com.itsaky.androidide.lsp.models.CompletionItemKind
import com.itsaky.androidide.lsp.models.DiagnosticItem
import com.itsaky.androidide.lsp.models.DiagnosticResult
import com.itsaky.androidide.lsp.models.DiagnosticSeverity
import com.itsaky.androidide.lsp.models.IndexedTextEdit
import com.itsaky.androidide.lsp.models.MarkupContent
import com.itsaky.androidide.lsp.models.MarkupKind
import com.itsaky.androidide.lsp.models.ParameterInformation
import com.itsaky.androidide.lsp.models.SignatureHelp
import com.itsaky.androidide.lsp.models.SignatureInformation
import com.itsaky.androidide.models.Location
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import org.eclipse.lsp4j.CompletionItem as LspCompletionItem
import org.eclipse.lsp4j.CompletionItemKind as LspCompletionItemKind
import org.eclipse.lsp4j.Diagnostic as LspDiagnostic
import org.eclipse.lsp4j.DiagnosticSeverity as LspDiagnosticSeverity
import org.eclipse.lsp4j.Location as LspLocation
import org.eclipse.lsp4j.LocationLink as LspLocationLink
import org.eclipse.lsp4j.MarkupContent as LspMarkupContent
import org.eclipse.lsp4j.ParameterInformation as LspParameterInformation
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange
import org.eclipse.lsp4j.SignatureHelp as LspSignatureHelp
import org.eclipse.lsp4j.SignatureInformation as LspSignatureInformation
import org.eclipse.lsp4j.TextEdit as LspTextEdit

internal fun Path.toLspUri(): String = toUri().toString()

internal fun String.toPath(): Path = Paths.get(URI(this))

internal fun Position.toLsp(): LspPosition = LspPosition(line, column)

internal fun Range.toLsp(): LspRange = LspRange(start.toLsp(), end.toLsp())

internal fun LspRange.toIde(): Range = Range(Position(start.line, start.character), Position(end.line, end.character))

internal fun LspRange.toIde(lines: LineOffsets): Range =
	Range(
		Position(start.line, start.character, lines.indexOf(start)),
		Position(end.line, end.character, lines.indexOf(end)),
	)

internal fun LspLocation.toIde(): Location = Location(uri.toPath(), range.toIde())

internal fun LspLocationLink.toIde(): Location = Location(targetUri.toPath(), targetSelectionRange.toIde())

internal fun Either<out List<LspLocation>, out List<LspLocationLink>>?.toIdeLocations(): List<Location> =
	when {
		this == null -> emptyList()
		isLeft -> left.map { it.toIde() }
		else -> right.map { it.toIde() }
	}

internal fun LspCompletionItem.toIde(prefix: String): CompletionItem {
	val edit = textEdit
	val insertion =
		when {
			edit == null -> insertText
			edit.isLeft -> edit.left.newText
			else -> edit.right.newText
		}
	return CompletionItem(
		label,
		detail ?: "",
		insertion,
		null,
		sortText,
		null,
		kind.toIde(),
		CompletionItem.matchLevel(filterText ?: label, prefix),
		null,
		null,
	)
}

internal fun LspCompletionItemKind?.toIde(): CompletionItemKind =
	when (this) {
		LspCompletionItemKind.Method -> CompletionItemKind.METHOD
		LspCompletionItemKind.Function -> CompletionItemKind.FUNCTION
		LspCompletionItemKind.Constructor -> CompletionItemKind.CONSTRUCTOR
		LspCompletionItemKind.Field -> CompletionItemKind.FIELD
		LspCompletionItemKind.Variable, LspCompletionItemKind.Constant -> CompletionItemKind.VARIABLE
		LspCompletionItemKind.Class, LspCompletionItemKind.Struct -> CompletionItemKind.CLASS
		LspCompletionItemKind.Interface -> CompletionItemKind.INTERFACE
		LspCompletionItemKind.Module -> CompletionItemKind.MODULE
		LspCompletionItemKind.Property -> CompletionItemKind.PROPERTY
		LspCompletionItemKind.Value -> CompletionItemKind.VALUE
		LspCompletionItemKind.Enum -> CompletionItemKind.ENUM
		LspCompletionItemKind.EnumMember -> CompletionItemKind.ENUM_MEMBER
		LspCompletionItemKind.Keyword -> CompletionItemKind.KEYWORD
		LspCompletionItemKind.Snippet -> CompletionItemKind.SNIPPET
		LspCompletionItemKind.TypeParameter -> CompletionItemKind.TYPE_PARAMETER
		else -> CompletionItemKind.NONE
	}

internal fun LspSignatureHelp.toIde(): SignatureHelp =
	SignatureHelp(
		signatures.map { it.toIde() },
		activeSignature ?: 0,
		activeParameter ?: 0,
	)

private fun LspSignatureInformation.toIde(): SignatureInformation =
	SignatureInformation(
		label,
		documentation.toIde(),
		parameters?.map { it.toIde() } ?: emptyList(),
	)

private fun LspParameterInformation.toIde(): ParameterInformation {
	val text = if (label.isLeft) label.left else "${label.right.first}-${label.right.second}"
	return ParameterInformation(text, documentation.toIde())
}

private fun Either<String, LspMarkupContent>?.toIde(): MarkupContent =
	when {
		this == null -> MarkupContent()
		isLeft -> MarkupContent(left, MarkupKind.PLAIN)
		else -> MarkupContent(right.value, if (right.kind == "markdown") MarkupKind.MARKDOWN else MarkupKind.PLAIN)
	}

internal fun List<LspDiagnostic>.toDiagnosticResult(
	file: Path,
	source: String,
	text: String?,
): DiagnosticResult {
	val lines = text?.let(::LineOffsets)
	return DiagnosticResult(
		file,
		map { diagnostic ->
			DiagnosticItem(
				diagnostic.message,
				diagnostic.code?.let { if (it.isLeft) it.left else it.right.toString() } ?: "",
				if (lines == null) diagnostic.range.toIde() else diagnostic.range.toIde(lines),
				diagnostic.source ?: source,
				diagnostic.severity.toIde(),
			)
		},
	)
}

private fun LspDiagnosticSeverity?.toIde(): DiagnosticSeverity =
	when (this) {
		LspDiagnosticSeverity.Error -> DiagnosticSeverity.ERROR
		LspDiagnosticSeverity.Warning -> DiagnosticSeverity.WARNING
		LspDiagnosticSeverity.Hint -> DiagnosticSeverity.HINT
		LspDiagnosticSeverity.Information, null -> DiagnosticSeverity.INFO
	}

internal fun List<LspTextEdit>.toIndexedEdits(text: String): MutableList<IndexedTextEdit> {
	val lines = LineOffsets(text)
	return map { IndexedTextEdit(lines.indexOf(it.range.start), lines.indexOf(it.range.end), it.newText) }
		.sortedByDescending { it.start }
		.toMutableList()
}

internal class LineOffsets(
	private val text: String,
) {
	private val starts: IntArray =
		buildList {
			add(0)
			text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
		}.toIntArray()

	fun indexOf(position: LspPosition): Int {
		if (position.line >= starts.size) return text.length
		val lineEnd = if (position.line + 1 < starts.size) starts[position.line + 1] - 1 else text.length
		return minOf(starts[position.line] + position.character, lineEnd)
	}
}
