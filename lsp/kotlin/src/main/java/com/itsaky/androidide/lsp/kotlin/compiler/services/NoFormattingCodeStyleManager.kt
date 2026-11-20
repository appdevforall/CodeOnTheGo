package com.itsaky.androidide.lsp.kotlin.compiler.services

import org.jetbrains.kotlin.com.intellij.formatting.FormattingMode
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Computable
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.Indent
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable

/**
 * A [CodeStyleManager] that exists only because `DiffLog.doActualPsiChange` looks one up to run its
 * PSI change with the formatter disabled, and no standalone Analysis API jar ships an implementation.
 * We never format through the PSI, so the "formatter disabled" actions just run their argument and
 * every formatting entry point fails loudly.
 */
internal class NoFormattingCodeStyleManager(
	private val project: Project,
) : CodeStyleManager() {
	override fun getProject(): Project = project

	override fun performActionWithFormatterDisabled(r: Runnable) = r.run()

	override fun <T : Throwable?> performActionWithFormatterDisabled(r: ThrowableRunnable<T>) = r.run()

	override fun <T> performActionWithFormatterDisabled(r: Computable<T>): T = r.compute()

	override fun isSequentialProcessingAllowed(): Boolean = false

	override fun reformat(element: PsiElement): PsiElement = unsupported()

	override fun reformat(
		element: PsiElement,
		canChangeWhiteSpacesOnly: Boolean,
	): PsiElement = unsupported()

	override fun reformatRange(
		element: PsiElement,
		startOffset: Int,
		endOffset: Int,
	): PsiElement = unsupported()

	override fun reformatRange(
		element: PsiElement,
		startOffset: Int,
		endOffset: Int,
		canChangeWhiteSpacesOnly: Boolean,
	): PsiElement = unsupported()

	override fun reformatText(
		file: PsiFile,
		startOffset: Int,
		endOffset: Int,
	) = unsupported()

	override fun reformatText(
		file: PsiFile,
		ranges: Collection<TextRange>,
	) = unsupported()

	override fun adjustLineIndent(
		file: PsiFile,
		rangeToAdjust: TextRange,
	) = unsupported()

	override fun adjustLineIndent(
		file: PsiFile,
		offset: Int,
	): Int = unsupported()

	override fun adjustLineIndent(
		document: Document,
		offset: Int,
	): Int = unsupported()

	override fun isLineToBeIndented(
		file: PsiFile,
		offset: Int,
	): Boolean = unsupported()

	override fun getLineIndent(
		file: PsiFile,
		offset: Int,
	): String? = unsupported()

	override fun getLineIndent(
		document: Document,
		offset: Int,
	): String? = unsupported()

	override fun getIndent(
		text: String,
		fileType: FileType,
	): Indent = unsupported()

	override fun fillIndent(
		indent: Indent,
		fileType: FileType,
	): String = unsupported()

	override fun zeroIndent(): Indent = unsupported()

	override fun reformatNewlyAddedElement(
		parent: ASTNode,
		addedElement: ASTNode,
	) = unsupported()

	private fun unsupported(): Nothing = throw UnsupportedOperationException("formatting is not supported")
}
