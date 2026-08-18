package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
import com.itsaky.androidide.lsp.kotlin.compiler.modules.analyzeMaybeDangling
import com.itsaky.androidide.lsp.kotlin.compiler.read
import com.itsaky.androidide.lsp.kotlin.utils.renderName
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("ExtractVariablePlanner")

/**
 * Computes the whole [ExtractionPlan] in one background analysis pass.
 *
 * The current [KtFile] is fetched *before* entering [read] -- blocking on
 * `getCurrentKtFile(...).get()` inside `project.read` deadlocks.
 *
 * Returns an empty plan both when there is genuinely nothing to extract and whenever anything in
 * this pipeline throws: the action framework only catches [IllegalArgumentException] and this runs on
 * a scope with no exception handler, so an uncaught throw would crash the app. Degrading to an empty
 * plan is always safe -- the action reports "nothing to extract" instead of rewriting anything.
 */
internal fun buildExtractionPlan(
	env: AbstractCompilationEnvironment,
	nioPath: Path,
	selectionStart: Int,
	selectionEnd: Int,
	documentVersion: Int,
	cancelChecker: ScheduledCancelChecker,
): ExtractionPlan =
	runCatching {
		val ktFile = env.ktSymbolIndex.getCurrentKtFile(nioPath).get() ?: return ExtractionPlan.empty()
		env.project.read {
			val syntax = candidateExpressionsAt(ktFile, selectionStart, selectionEnd)
			if (syntax.expressions.isEmpty()) return@read ExtractionPlan.empty(ktFile.text, documentVersion)

			analyzeMaybeDangling(ktFile, AnalysisPriority.INTERACTIVE, cancelChecker) {
				ExtractionPlan(
					fileText = ktFile.text,
					documentVersion = documentVersion,
					candidates = syntax.expressions.mapNotNull { candidateFor(it) },
				)
			}
		}
	}.getOrElse { error ->
		logger.warn("Failed to build extract-variable plan for {}", nioPath, error)
		ExtractionPlan.empty()
	}

/**
 * Turns one syntactic candidate into a [CandidateExpression], or null when it should not be offered.
 *
 * Dropped when the expression produces no useful value (`Unit`, `Nothing` -- `val u = println(x)`
 * compiles but is pointless) or when nothing remains of its legal scope chain.
 */
@OptIn(KaExperimentalApi::class)
private fun KaSession.candidateFor(expression: KtExpression): CandidateExpression? {
	val type = runCatching { expression.expressionType }.getOrNull()
	if (type == null || isValuelessType(type)) return null

	val frames = truncateAtCeiling(enclosingScopeFrames(expression), referencedDeclarationCeiling(expression))
	if (frames.isEmpty()) return null

	val span = TextSpan(expression.textRange.startOffset, expression.textRange.endOffset)
	val file = expression.containingKtFile
	val scopes = frames.mapNotNull { scopeOptionFor(expression, span, it, file) }
	if (scopes.isEmpty()) return null
	val takenNames = namesInScopeAt(expression)

	return CandidateExpression(
		label = collapseForLabel(expression.text),
		span = span,
		suggestedName = suggestVariableName(expression, runCatching { renderName(type) }.getOrNull(), takenNames),
		takenNames = takenNames,
		scopes = scopes,
	)
}

/**
 * Builds one scope option, resolving its occurrence set and fixing up expression-body details.
 *
 * Returns null when the rung cannot be honoured: converting an expression body whose return type is
 * neither declared nor renderable would emit a block body that does not compile, and declining is
 * always safe -- the decline-rather-than-rewrite principle that ADR 0013 records, landing alongside
 * extract method (ADFA-5080).
 */
private fun KaSession.scopeOptionFor(
	expression: KtExpression,
	span: TextSpan,
	frame: ScopeFrame,
	file: KtFile,
): ScopeOption? {
	val matches = findOccurrences(expression, frame.scopeElement, frame.searchRange)
	val writes = writeOffsetsFor(expression, frame.scopeElement)
	val sound = excludeUnsoundOccurrences(matches, span, writes)
	val occurrences = servableOccurrences(file.text, frame.anchorForm, sound, span)

	val anchorForm =
		when (val form = frame.anchorForm) {
			is AnchorForm.ExistingBlock -> {
				/*
				 * The rewrite refuses this geometry, so refusing it here too is what turns a sheet whose
				 * confirm must fail into an up-front "nothing to extract". The candidate's own span is
				 * tested here; servableOccurrences is what makes the first served target placeable when
				 * replace-all is on.
				 */
				if (blockPlacementFor(file.text, form, span) is BlockPlacement.Refused) return null
				form
			}

			is AnchorForm.ConvertExpressionBody -> {
				val declaration = frame.scopeElement.parent as? KtDeclarationWithBody
				val mustWriteType = declaration != null && !declaration.declaresReturnType()
				val rendered = if (mustWriteType) returnTypeTextOf(declaration, file) else null
				val (needsReturn, returnTypeText) =
					normalizeExpressionBodyReturn(expressionBodyNeedsReturn(frame.scopeElement), rendered)
				/*
				 * A block body with no declared type returns Unit, so a return that needs a type it
				 * cannot get declines the rung -- the decline-rather-than-rewrite principle of ADR 0013.
				 */
				if (needsReturn && mustWriteType && returnTypeText == null) return null
				form.copy(needsReturn = needsReturn, returnTypeText = returnTypeText)
			}

			else -> {
				form
			}
		}

	return ScopeOption(label = frame.label, anchorForm = anchorForm, occurrences = occurrences)
}

/** Whether the declaration spells its return type out, in which case nothing needs writing. */
private fun KtDeclarationWithBody.declaresReturnType(): Boolean =
	when (this) {
		// KtPropertyAccessor.returnTypeReference is deprecated in favour of the identical typeReference.
		is KtPropertyAccessor -> typeReference != null

		is KtCallableDeclaration -> typeReference != null

		else -> false
	}

/** The declaration's resolved return type, or null when it cannot be resolved. */
private fun KaSession.returnTypeOf(declaration: KtDeclarationWithBody): KaType? =
	runCatching { (declaration.symbol as? KaCallableSymbol)?.returnType }.getOrNull()

/** The declaration's return type as source text, shortened where the file can resolve it. */
private fun KaSession.returnTypeTextOf(
	declaration: KtDeclarationWithBody,
	file: KtFile,
): String? {
	val type = returnTypeOf(declaration) ?: return null
	val rendered = renderedTypeTextOrNull(type) ?: return null
	return shortenTypeText(rendered, importedNamesOf(file), starImportedPackagesOf(file))
}

/**
 * Whether converting an expression body to a block body needs a `return`.
 *
 * False only for a `Unit`-returning function, where `return expr` on a non-`Unit` expression would
 * not compile and is unnecessary anyway. `Nothing` is deliberately not folded in here even though
 * [isValuelessType] treats it like `Unit` for the R4 candidate filter -- a `Nothing`-returning
 * function needs its `return` and its written-out type kept, or a caller using it in a `Nothing`
 * position (`x ?: boom()`) stops compiling. Defaults to true, which is right for everything else
 * including property accessors.
 */
private fun KaSession.expressionBodyNeedsReturn(bodyExpression: PsiElement): Boolean {
	val declaration = bodyExpression.parent as? KtDeclarationWithBody ?: return true
	val returnType = returnTypeOf(declaration) ?: return true
	return !isUnitReturnType(returnType)
}

/**
 * Whether [type] is `Unit`, with the rendered text as the fallback answer.
 *
 * A throw from `isUnitType` must not read as "not `Unit`": that writes the very `Unit` it failed to
 * recognise into the signature and wraps a `Unit` call in a pointless `return`.
 */
private fun KaSession.isUnitReturnType(type: KaType): Boolean =
	runCatching { type.isUnitType }.getOrNull()
		?: renderedTypeTextOrNull(type)?.let(::isUnitTypeText)
		?: false

/** `Unit` and `Nothing` carry no value worth binding to a `val`. */
private fun KaSession.isValuelessType(type: KaType): Boolean = runCatching { type.isUnitType || type.isNothingType }.getOrDefault(false)
