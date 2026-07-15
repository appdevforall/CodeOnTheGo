package com.itsaky.androidide.lsp.kotlin.utils

import com.itsaky.androidide.lsp.models.TextEdit
import com.itsaky.androidide.models.Position
import com.itsaky.androidide.models.Range
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("EditExts")

fun TextRange.toRange(containingFile: PsiFile): Range {
	val doc = PsiDocumentManager.getInstance(containingFile.project).getDocument(containingFile)
		?: return Range.NONE
	val startLine = doc.getLineNumber(startOffset)
	val startCol = startOffset - doc.getLineStartOffset(startLine)
	val endLine = doc.getLineNumber(endOffset)
	val endCol = endOffset - doc.getLineStartOffset(endLine)
	return Range(
		start = Position(
			line = startLine,
			column = startCol,
			index = startOffset,
		), end = Position(
			line = endLine,
			column = endCol,
			index = endOffset,
		)
	)
}

context(ctx: AnalysisContext)
internal fun insertImport(fqn: String): List<TextEdit> =
	insertImport(ctx.ktFile, fqn)

internal fun insertImport(ktFile: KtFile, fqn: String): List<TextEdit> {
	val imports = ktFile.importDirectives
	val importText = "import $fqn"
	for (import in imports) {
		val thisFqn = import.importedFqName?.asString() ?: ""
		if (thisFqn == fqn) return emptyList()
		if (thisFqn.substringBeforeLast('.') + ".*" == fqn) return emptyList()

		if (fqn < thisFqn) {
			logger.info("insert '{}' before '{}'", importText, thisFqn)
			return insertBefore(import, importText + System.lineSeparator())
		}
	}

	if (imports.isNotEmpty()) {
		val last = imports[imports.size - 1]
		logger.info("insert {} after last import: {}", importText, last.text)
		return insertAfter(last, System.lineSeparator() + importText)
	}

	ktFile.packageDirective?.also { pkg ->
		logger.info("insert {} after package stmt: {}", importText, pkg.text)
		return insertAfter(pkg, System.lineSeparator() + importText)
	}

	logger.info("insert {} at top", importText)
	val start = Position(0, 0)
	return listOf(
		TextEdit(
			range = Range(start, start),
			newText = importText + System.lineSeparator()
		)
	)
}

internal fun insertBefore(element: PsiElement, text: String): List<TextEdit> {
	val range = rangeOf(element)
	return listOf(
		TextEdit(
			range = Range(range.start, range.start),
			newText = text
		)
	)
}

internal fun insertAfter(element: PsiElement, text: String): List<TextEdit> {
	val range = rangeOf(element)
	return listOf(
		TextEdit(
			range = Range(range.end, range.end),
			newText = text
		)
	)
}

internal fun rangeOf(element: PsiElement, containingFile: PsiFile = element.containingFile): Range {
	return element.textRange.toRange(containingFile)
}