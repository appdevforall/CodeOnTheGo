package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("InlineVariablePlanner")

/**
 * Computes the whole [InlineVariablePlan] in one background analysis pass.
 *
 * The current [KtFile] is fetched *before* entering [read] -- blocking on `getCurrentKtFile(...).get()`
 * inside `project.read` deadlocks.
 *
 * Anything thrown in this pipeline degrades to [InlineRefusal.CouldNotAnalyse] plus a log line: the
 * action framework catches only `IllegalArgumentException` and this runs on a scope with no exception
 * handler, so an uncaught throw would crash the app. Cancellation is the exception -- it is re-thrown,
 * since a cancelled action has no result to report.
 */
internal fun buildInlineVariablePlan(
	env: AbstractCompilationEnvironment,
	nioPath: Path,
	offset: Int,
	documentVersion: Int,
	cancelChecker: ScheduledCancelChecker,
): InlineVariablePlan =
	runCatching {
		val ktFile =
			env.ktSymbolIndex.getCurrentKtFile(nioPath).get()
				?: return InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse)

		env.project.read {
			val fileText = ktFile.text
			analyzeMaybeDangling(ktFile, AnalysisPriority.INTERACTIVE, cancelChecker) {
				planFor(ktFile, fileText, offset, documentVersion)
			}
		}
	}.getOrElse { error ->
		if (error is CancellationException) throw error
		logger.warn("Failed to build inline-variable plan for {}", nioPath, error)
		InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse)
	}

/** The target declaration and how the cursor found it, or the reason there is none. */
private sealed interface TargetResolution {
	data class Resolved(
		val target: KtProperty,
		val cursorPosition: InlineCursorPosition,
	) : TargetResolution

	data class Refused(
		val refusal: InlineRefusal,
	) : TargetResolution
}

private fun KaSession.planFor(
	ktFile: KtFile,
	fileText: String,
	offset: Int,
	documentVersion: Int,
): InlineVariablePlan {
	val resolution = resolveTarget(ktFile, offset)
	if (resolution is TargetResolution.Refused) {
		return InlineVariablePlan.refused(resolution.refusal, fileText, documentVersion)
	}
	val resolved = resolution as TargetResolution.Resolved
	val target = resolved.target
	val name = target.name ?: return InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse, fileText, documentVersion)

	// Order matters: a `val x: Int` with a later `x = 1` carries both an absent initializer and an
	// explicit type, and "has no value at its declaration" is the truthful reason.
	val initializer =
		target.initializer
			?: return InlineVariablePlan.refused(InlineRefusal.NoInitializer(name), fileText, documentVersion)
	target.typeReference?.let { typeReference ->
		return InlineVariablePlan.refused(
			InlineRefusal.DeclaredTypeIsLoadBearing(name, typeReference.text),
			fileText,
			documentVersion,
		)
	}

	val searchRoot =
		enclosingExecutableBody(target)
			?: return InlineVariablePlan.refused(InlineRefusal.CouldNotAnalyse, fileText, documentVersion)

	val reads = mutableListOf<KtSimpleNameExpression>()
	val targetWriteOffsets = mutableListOf<Int>()
	for (candidate in PsiTreeUtil.collectElementsOfType(searchRoot, KtSimpleNameExpression::class.java)) {
		if (!resolvesToTarget(candidate, target)) continue
		// A write is a *cause* of the cutoff, never a candidate for substitution.
		if (candidate.isWriteTarget()) targetWriteOffsets += candidate.textRange.startOffset else reads += candidate
	}

	if (reads.isEmpty()) {
		return InlineVariablePlan.refused(InlineRefusal.NeverUsed(name), fileText, documentVersion)
	}

	val declarationEnd = target.textRange.endOffset
	val cutoff = cutoffAfter(initializer, searchRoot, targetWriteOffsets, declarationEnd)

	val references =
		reads.map { read ->
			val entry = read.parent as? KtSimpleNameStringTemplateEntry
			val span =
				if (entry != null) {
					TextSpan(entry.textRange.startOffset, entry.textRange.endOffset)
				} else {
					TextSpan(read.textRange.startOffset, read.textRange.endOffset)
				}
			InlineReference(
				span = span,
				isShortTemplateEntry = entry != null,
				exclusion = if (span.start >= cutoff) InlineExclusion.PastCutoff else null,
			)
		}

	val cursorReferenceIndex =
		if (resolved.cursorPosition == InlineCursorPosition.Reference) {
			references.indexOfFirst { offset >= it.span.start && offset <= it.span.end }
		} else {
			-1
		}
	if (resolved.cursorPosition == InlineCursorPosition.Reference) {
		val cursorReference = references.getOrNull(cursorReferenceIndex)
		// Rewriting every site except the one under the user's finger reads as having done nothing.
		if (cursorReference == null || !cursorReference.isInlinable) {
			return InlineVariablePlan.refused(InlineRefusal.ReferenceNotInlinable(name), fileText, documentVersion)
		}
	}

	val inlinable = references.count { it.isInlinable }
	if (inlinable == 0) {
		return InlineVariablePlan.refused(InlineRefusal.NothingInlinable(name), fileText, documentVersion)
	}

	return InlineVariablePlan(
		fileText = fileText,
		documentVersion = documentVersion,
		variableName = name,
		declarationSpan = TextSpan(target.textRange.startOffset, declarationEnd),
		initializerText = initializer.text,
		initializerNeedsParentheses = needsParentheses(initializer),
		references = references,
		cursorPosition = resolved.cursorPosition,
		cursorReferenceIndex = cursorReferenceIndex,
		// The deletion rule's second clause is not redundant: a `var` whose reads were all inlined can still have a
		// later `x = 5` assigning to it, so the declaration is still needed.
		canDeleteDeclaration = inlinable == references.size && targetWriteOffsets.isEmpty(),
		modes = modesFor(resolved.cursorPosition, inlinable),
		refusal = null,
	)
}

/**
 * The target the cursor points at, from either of the two cursor positions.
 *
 * Function parameters, lambda parameters, `it`, loop variables, `catch` parameters and destructuring
 * entries are not [KtProperty] at all, so they are excluded by construction. The three refusals made
 * explicitly here are the positions a user can reasonably put the cursor in and deserves to be told
 * about.
 */
private fun KaSession.resolveTarget(
	ktFile: KtFile,
	offset: Int,
): TargetResolution {
	val leaf =
		ktFile.findElementAt(offset)
			?: ktFile.findElementAt(offset - 1)
			?: return TargetResolution.Refused(InlineRefusal.NotAVariable)

	PsiTreeUtil.getParentOfType(leaf, KtDestructuringDeclaration::class.java, false)?.let {
		return TargetResolution.Refused(InlineRefusal.DestructuringDeclaration)
	}

	val declaration = PsiTreeUtil.getParentOfType(leaf, KtProperty::class.java, false)
	val nameRange = declaration?.nameIdentifier?.textRange
	if (declaration != null && nameRange != null && offset >= nameRange.startOffset && offset <= nameRange.endOffset) {
		if (!declaration.isLocal) return TargetResolution.Refused(InlineRefusal.NotALocalVariable)
		return TargetResolution.Resolved(declaration, InlineCursorPosition.Declaration)
	}

	val reference =
		PsiTreeUtil.getParentOfType(leaf, KtSimpleNameExpression::class.java, false)
			?: return TargetResolution.Refused(InlineRefusal.NotAVariable)
	val referenced =
		runCatching {
			reference.mainReference
				?.resolveToSymbols()
				?.firstNotNullOfOrNull { symbol -> runCatching { symbol.psi }.getOrNull() }
		}.getOrNull() ?: return TargetResolution.Refused(InlineRefusal.NotAVariable)

	if (referenced is KtProperty) {
		// A reference can resolve into another file (or a library); this plan model is one file's text
		// plus one document version, so anything else is out of scope.
		if (referenced.containingFile != ktFile) return TargetResolution.Refused(InlineRefusal.NotALocalVariable)
		if (!referenced.isLocal) return TargetResolution.Refused(InlineRefusal.NotALocalVariable)
		return TargetResolution.Resolved(referenced, InlineCursorPosition.Reference)
	}
	return TargetResolution.Refused(InlineRefusal.NotAVariable)
}

/** Whether [reference] resolves to [target], compared by source PSI identity as `Occurrences.kt` does. */
private fun KaSession.resolvesToTarget(
	reference: KtSimpleNameExpression,
	target: KtProperty,
): Boolean =
	runCatching {
		reference.mainReference?.resolveToSymbols()?.any { symbol ->
			runCatching { symbol.psi }.getOrNull() === target
		} == true
	}.getOrDefault(false)

/**
 * The first offset after the declaration where the inlined value stops being the value the
 * declaration produced: either a write to the target itself -- only possible for a `var` -- or a
 * write to a mutable the initializer reads.
 *
 * [Int.MAX_VALUE] when nothing writes, so every reference compares as before the cutoff.
 */
private fun KaSession.cutoffAfter(
	initializer: KtExpression,
	searchRoot: PsiElement,
	targetWriteOffsets: List<Int>,
	declarationEnd: Int,
): Int =
	(writeOffsetsFor(initializer, searchRoot) + targetWriteOffsets)
		.filter { it >= declarationEnd }
		.minOrNull() ?: Int.MAX_VALUE

/**
 * Parenthesisation, decided by classifying the initializer alone: no parentheses for a single
 * atomic or postfix expression, parentheses for everything else.
 *
 * Site-sensitive precedence comparison was rejected: it has to be right about every parent context,
 * and no preview is shown on the common path. The cost of the stricter rule is a redundant
 * `return (a + b)`; the cost of the cleverer one is a miscompile the user did not see coming.
 */
private fun needsParentheses(initializer: KtExpression): Boolean =
	when (initializer) {
		is KtConstantExpression,
		is KtStringTemplateExpression,
		is KtSimpleNameExpression,
		is KtThisExpression,
		is KtSuperExpression,
		is KtCallExpression,
		is KtQualifiedExpression,
		is KtArrayAccessExpression,
		is KtParenthesizedExpression,
		is KtLambdaExpression,
		is KtNamedFunction,
		is KtObjectLiteralExpression,
		is KtCollectionLiteralExpression,
		is KtCallableReferenceExpression,
		is KtClassLiteralExpression,
		is KtPostfixExpression,
		-> false

		else -> true
	}
