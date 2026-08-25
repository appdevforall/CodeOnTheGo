package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.compiler.AbstractCompilationEnvironment
import com.itsaky.androidide.lsp.kotlin.compiler.modules.AnalysisPriority
import com.itsaky.androidide.lsp.kotlin.compiler.modules.ScheduledCancelChecker
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
		env.ktSymbolIndex.withLiveKtFile(nioPath) { live ->
			if (live.isStale) {
				/*
				 * Joining another feature's scope hands over its text, which can be older than the buffer.
				 * The caller stamps `documentVersion` from the live buffer, so the apply-time version guard
				 * would compare an honest stamp against text one edit behind and pass - and offsets computed
				 * here would replace the wrong span. Refusing is the only safe answer.
				 */
				logger.debug("refusing extract-variable plan for {}: pinned text is behind the buffer", nioPath)
				return@withLiveKtFile ExtractionPlan.empty()
			}

			live.read { ktFile ->
				val syntax = candidateExpressionsAt(ktFile, selectionStart, selectionEnd)
				if (syntax.expressions.isEmpty()) return@read ExtractionPlan.empty(ktFile.text, documentVersion)

				/* PsiFileImpl.getText() allocates a fresh String each call, so the plan pass reads it once and
				 * threads it down to every candidate and rung. */
				val fileText = ktFile.text
				live.analyzing(AnalysisPriority.INTERACTIVE, cancelChecker) {
					ExtractionPlan(
						fileText = fileText,
						documentVersion = documentVersion,
						candidates = syntax.expressions.mapNotNull { candidateFor(it, fileText) },
					)
				}
			}
		} ?: ExtractionPlan.empty()
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
private fun KaSession.candidateFor(
	expression: KtExpression,
	fileText: String,
): CandidateExpression? {
	val type = runCatching { expression.expressionType }.getOrNull()
	if (type == null || isValuelessType(type)) return null

	val frames = truncateAtCeiling(enclosingScopeFrames(expression), referencedDeclarationCeiling(expression))
	if (frames.isEmpty()) return null

	val span = TextSpan(expression.textRange.startOffset, expression.textRange.endOffset)
	val file = expression.containingKtFile
	val scopes = frames.mapNotNull { scopeOptionFor(expression, span, it, file, fileText) }
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
 * Builds one scope option: settles the anchor form, then resolves the occurrence set it can serve.
 *
 * Returns null when the rung cannot be honoured at all, either because the block's geometry refuses
 * the declaration or because an expression-body conversion cannot be reconciled. Both declines run
 * before the occurrence search, so a refused rung costs nothing.
 *
 * [fileText] must be the text the plan's spans were computed against, since [blockPlacementFor] and
 * [servableOccurrences] index into it unchecked.
 */
private fun KaSession.scopeOptionFor(
	expression: KtExpression,
	span: TextSpan,
	frame: ScopeFrame,
	file: KtFile,
	fileText: String,
): ScopeOption? {
	val anchorForm =
		when (val form = frame.anchorForm) {
			is AnchorForm.ExistingBlock -> {
				/*
				 * The rewrite refuses this geometry, so refusing it here too is what turns a sheet whose
				 * confirm must fail into an up-front "nothing to extract". The candidate's own span is
				 * tested here; servableOccurrences is what makes the first served target placeable when
				 * replace-all is on.
				 */
				if (blockPlacementFor(fileText, form, span) is BlockPlacement.Refused) return null
				form
			}

			is AnchorForm.ConvertExpressionBody -> {
				convertExpressionBodyForm(form, frame.scopeElement, file) ?: return null
			}

			is AnchorForm.WrapInBraces -> {
				form
			}
		}

	val matches = findOccurrences(expression, frame.scopeElement, frame.searchRange)
	val writes = writeOffsetsFor(expression, frame.scopeElement)
	val sound = excludeUnsoundOccurrences(matches, span, writes)
	val occurrences = servableOccurrences(fileText, anchorForm, sound, span)

	return ScopeOption(label = frame.label, anchorForm = anchorForm, occurrences = occurrences)
}

/**
 * Fills in the `return` and written-type details of an expression-body rung, or null to decline it.
 *
 * A block body with no declared type returns `Unit`, so a `return` that needs a type neither declared
 * nor renderable would emit a body that does not compile. Declining is always safe -- the
 * decline-rather-than-rewrite principle that ADR 0013 records, landing alongside extract method
 * (ADFA-5080).
 */
private fun KaSession.convertExpressionBodyForm(
	form: AnchorForm.ConvertExpressionBody,
	bodyExpression: PsiElement,
	file: KtFile,
): AnchorForm.ConvertExpressionBody? {
	val declaration = bodyExpression.parent as? KtDeclarationWithBody
	val mustWriteType = declaration != null && !declaration.declaresReturnType()
	val rendered = if (mustWriteType) returnTypeTextOf(declaration, file) else null
	val (needsReturn, returnTypeText) =
		normalizeExpressionBodyReturn(expressionBodyNeedsReturn(bodyExpression), rendered)
	if (needsReturn && mustWriteType && returnTypeText == null) return null
	return form.copy(needsReturn = needsReturn, returnTypeText = returnTypeText)
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
