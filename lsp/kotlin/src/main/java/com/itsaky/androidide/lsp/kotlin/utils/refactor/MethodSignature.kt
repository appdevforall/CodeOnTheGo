package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaCallableMemberCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundArrayAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCompoundVariableAccessCall
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.KaReceiverValue
import org.jetbrains.kotlin.analysis.api.resolution.successfulCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaBackingFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaReceiverParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaAnnotatedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.analysis.api.types.KaFlexibleType
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionWithLabel
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/** The name of the statement-range suggestion; there is no expression to read a name from (R12). */
private const val STATEMENT_RANGE_NAME = "extracted"

private const val COMPOSABLE_FQ_NAME = "androidx.compose.runtime.Composable"

/** What a receiver-binding lambda is called in the refusal when it is not a call argument. */
private const val UNNAMED_SCOPING_CONSTRUCT = "lambda"

private const val BACKING_FIELD_NAME = "field"

private const val COROUTINE_CONTEXT_NAME = "coroutineContext"

/** As [renderedTypeTextOrNull] prints it. A `Unit` return type is left off the signature entirely. */
private const val UNIT_TYPE_TEXT = "kotlin.Unit"

/** Either a derived candidate or the reason there is not one. */
internal sealed interface SignatureResult {
	data class Success(
		val candidate: ExtractMethodCandidate,
	) : SignatureResult

	data class Refused(
		val refusal: ExtractionRefusal,
	) : SignatureResult
}

/**
 * Derives one candidate from [elements] -- a single expression, or the statement range.
 *
 * Ordered so the cheapest refusals come first and nothing expensive runs for a region that is going
 * to be declined anyway. MUST be called inside an analysis session.
 */
internal fun KaSession.buildCandidate(
	elements: List<KtExpression>,
	isExpression: Boolean,
	fileText: String,
): SignatureResult {
	val first = elements.first()
	val last = elements.last()
	val span = TextSpan(first.textRange.startOffset, last.textRange.endOffset)
	val enclosing = enclosingDeclaration(first) ?: return refuse(ExtractionRefusal.NotASingleRegion)

	/*
	 * enclosingDeclaration skips a nameless KtNamedFunction, so an anonymous extension function
	 * (`fun String.() { ... }` used as a value) between the region and enclosing is invisible to it,
	 * and receiverTypeTextOf(enclosing) then reads the outer declaration's receiver instead of the
	 * anonymous function's own -- the region can depend on a receiver the emitted function never gets.
	 * Declined unconditionally rather than only when the region actually uses the receiver: anonymous
	 * extension functions are rare, and this is far cheaper than the resolution innerImplicitReceiver
	 * would need to tell real receiver use apart from an unrelated capture.
	 */
	if (anonymousExtensionFunctionBetween(first, enclosing)) {
		return refuse(ExtractionRefusal.InnerImplicitReceiver("anonymous function"))
	}

	val typeParameterNames = typeParameterNamesOf(enclosing)
	typeParameterIn(typeParameterNames, elements)?.let { return refuse(ExtractionRefusal.UsesTypeParameter(it)) }
	if (usesBackingField(enclosing, elements)) return refuse(ExtractionRefusal.UsesBackingField)
	innerImplicitReceiver(enclosing, elements, span)?.let { return refuse(ExtractionRefusal.InnerImplicitReceiver(it)) }
	reassignedOuterVar(enclosing, elements, span)?.let { return refuse(ExtractionRefusal.ReassignsOuterVar(it)) }

	val tailReturn = !isExpression && isTailReturn(elements, span)
	if (!tailReturn && hasExit(elements, span)) return refuse(ExtractionRefusal.ExitsRegion)

	val outputs = if (isExpression) RegionOutputs.NONE else outputsOf(enclosing, elements, span)
	// Only a single plain `val`/`var` can come back as the return value. Everything else the region
	// declares and the following code still needs is refused rather than silently dropped (R7), split
	// by which situation it is: two values genuinely cannot fit in one return, while a lone
	// destructuring entry, local `fun` or reassigned local is one value the call site cannot receive.
	if (outputs.declarations.size > 1) {
		return refuse(ExtractionRefusal.MultipleOutputs(outputs.declarations.mapNotNull { it.name }))
	}
	val declared = outputs.declarations.singleOrNull()
	if (declared != null && (declared !is KtProperty || outputs.writtenAfter.isNotEmpty())) {
		return refuse(ExtractionRefusal.OutputNotReturnable(declared.name.orEmpty()))
	}
	val output = declared as? KtProperty
	// The tail-return exception holds only when nothing else flows out (R8).
	if (tailReturn && output != null) return refuse(ExtractionRefusal.ExitsRegion)

	val parameters =
		when (val captured = capturedParameters(enclosing, elements, span)) {
			is CaptureResult.Captured -> captured.parameters
			is CaptureResult.Refused -> return refuse(captured.refusal)
		}

	val returnTypeText =
		when {
			isExpression -> {
				renderedTypeOrNull(first) ?: return refuse(ExtractionRefusal.UnrenderableType)
			}

			tailReturn -> {
				// A secondary constructor's symbol returns the constructed class, but its `return`
				// carries no value -- so the extracted tail is `Unit`, and `return extracted(...)` on a
				// `Unit` call is legal inside a constructor. (`init` needs no rule: `return` is illegal
				// there, so no tail return can reach here.)
				when (enclosing) {
					is KtSecondaryConstructor -> UNIT_TYPE_TEXT
					else -> enclosingReturnType(enclosing) ?: return refuse(ExtractionRefusal.UnrenderableType)
				}
			}

			output != null -> {
				renderedDeclarationType(output) ?: return refuse(ExtractionRefusal.UnrenderableType)
			}

			else -> {
				null
			}
		}.takeUnless { it == UNIT_TYPE_TEXT }

	val receiverTypeText = receiverTypeTextOf(enclosing)

	// The syntactic check above misses an inferred type argument, which names no type anywhere in the
	// region. The rendered signature is the last place to catch it before it is emitted (R10), and it
	// has to cover every slot the signature prints -- the receiver included.
	renderedTypeParameterIn(
		typeParameterNames,
		parameters.map { it.typeText } + listOfNotNull(returnTypeText, receiverTypeText),
	)?.let { return refuse(ExtractionRefusal.UsesTypeParameter(it)) }

	val body =
		when {
			isExpression -> ExtractedBody.ExpressionBody(needsReturn = returnTypeText != null)
			output != null -> ExtractedBody.StatementBody(trailingReturn = "return ${output.name.orEmpty()}")
			else -> ExtractedBody.StatementBody(trailingReturn = null)
		}

	val callSite =
		when {
			tailReturn -> CallSiteForm.Return
			output != null -> CallSiteForm.AssignOutput(output.name.orEmpty())
			else -> CallSiteForm.Call
		}

	// A getter is not a place a function can follow -- inserting there lands between the accessors of
	// a `var` and does not parse -- so the new member goes after the whole property (R4). The accessor
	// itself stays the capture boundary everywhere else.
	val anchor = (enclosing as? KtPropertyAccessor)?.property ?: enclosing
	val isLocalTarget = anchor.parent is KtBlockExpression
	val takenNames = takenNamesFor(enclosing, anchor, isLocalTarget)
	val modifiers =
		buildList {
			// A local function joins a block, and a visibility modifier on one does not compile.
			if (!isLocalTarget) add("private")
			if (usesSuspend(elements)) add("suspend")
		}

	return SignatureResult.Success(
		ExtractMethodCandidate(
			label = collapseForLabel(fileText.substring(span.start, span.end)),
			span = span,
			suggestedName =
				if (isExpression) {
					suggestVariableName(first, renderedTypeOrNull(first), takenNames)
				} else {
					uniqueName(STATEMENT_RANGE_NAME, takenNames)
				},
			takenNames = takenNames,
			annotations = if (usesComposable(elements)) listOf("@Composable") else emptyList(),
			modifiers = modifiers,
			receiverTypeText = receiverTypeText,
			parameters = parameters,
			returnTypeText = returnTypeText,
			body = body,
			callSite = callSite,
			// A local function is only visible from its declaration onward, so it has to go *before* the
			// anchor that calls it. Sound in general: everything the anchor's body can reach is already
			// declared above the anchor. Every other target keeps the new member after its anchor (R4).
			insertOffset = if (isLocalTarget) anchor.textRange.startOffset else anchor.textRange.endOffset,
			insertIndent = leadingIndentAt(fileText, anchor.textRange.startOffset),
		),
	)
}

private fun refuse(refusal: ExtractionRefusal): SignatureResult = SignatureResult.Refused(refusal)

/**
 * The named function, accessor, `init` block or constructor whose body holds [element]. Lambdas and
 * anonymous functions are skipped: the new function is a sibling of the enclosing *named* declaration
 * (R4), and their captures become parameters.
 */
private fun enclosingDeclaration(element: PsiElement): KtDeclaration? {
	var current: PsiElement? = element.parent
	while (current != null) {
		when (current) {
			is KtNamedFunction -> {
				// PSI gives an anonymous `fun(...) { }` the same node type as a named function, with a null
				// name. It is a value, not a declaration a sibling can follow: anchoring on it inserts the
				// new function into an argument list or a property initializer, and the file stops parsing.
				if (current.name != null) return current
			}

			is KtPropertyAccessor, is KtAnonymousInitializer, is KtSecondaryConstructor -> {
				return current
			}

			is KtClassOrObject -> {
				return null
			}
		}
		current = current.parent
	}
	return null
}

/**
 * Whether an anonymous extension function -- a nameless `KtNamedFunction` with a receiver -- sits
 * between [element] and [enclosing]. Every such ancestor contains [element], so it is necessarily
 * outside the region; no separate in-region check is needed.
 */
private fun anonymousExtensionFunctionBetween(
	element: PsiElement,
	enclosing: KtDeclaration,
): Boolean {
	var current: PsiElement? = element.parent
	while (current != null && current != enclosing) {
		if (current is KtNamedFunction && current.name == null && current.receiverTypeReference != null) {
			return true
		}
		current = current.parent
	}
	return false
}

/** Whether [element] is inside the region's span. */
private fun inRegion(
	element: PsiElement,
	span: TextSpan,
): Boolean = element.textRange.startOffset >= span.start && element.textRange.endOffset <= span.end

private fun simpleNamesIn(elements: List<KtExpression>): List<KtSimpleNameExpression> =
	elements.flatMap { PsiTreeUtil.collectElementsOfType(it, KtSimpleNameExpression::class.java) }

private fun <T : PsiElement> descendantsOf(
	elements: List<KtExpression>,
	type: Class<T>,
): List<T> = elements.flatMap { PsiTreeUtil.collectElementsOfType(it, type) }

/**
 * The name of a class declared inside [enclosing] that [type] is written in terms of, or null.
 *
 * A value of such a type survives the move, but its type name does not resolve at the insertion
 * point, so no parameter can be written for it. Type arguments are searched too: `List<Holder>` is
 * just as unwritable as `Holder`.
 */
private fun KaSession.localTypeNameIn(
	type: KaType?,
	enclosing: KtDeclaration,
): String? {
	val classType = ((type as? KaFlexibleType)?.lowerBound ?: type) as? KaClassType ?: return null
	val psi = runCatching { classType.symbol.psi }.getOrNull()
	if (psi != null && PsiTreeUtil.isAncestor(enclosing, psi, true)) {
		return (classType.symbol as? KaNamedSymbol)?.name?.asString()
	}
	return classType.typeArguments.firstNotNullOfOrNull { localTypeNameIn(it.type, enclosing) }
}

/**
 * A captured declaration is one the region references whose PSI lies inside the enclosing
 * declaration but outside the region itself. Anything else -- a class member, a top-level
 * declaration, an import -- resolves unchanged from the new function's body (R5).
 *
 * Declines rather than emitting text that will not compile: a type that cannot be written out as
 * source, or a value the region only uses through a smart cast.
 */
private fun KaSession.capturedParameters(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): CaptureResult {
	val parameters = mutableListOf<MethodParameter>()
	val seen = mutableSetOf<Any>()

	for (reference in simpleNamesIn(elements).sortedBy { it.textRange.startOffset }) {
		// Deliberately no "skip a qualified selector" guard here. A selector can still resolve to a
		// declaration inside the enclosing declaration -- a local extension `fun` called as `h.twice()`
		// -- which goes out of scope once the region moves, and skipping it emits a body that no longer
		// resolves. The ancestor test below already lets every selector resolving to a non-local member
		// through, which is what a guard would have bought.
		val resolved =
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() ?: continue
		val name = reference.getReferencedName()

		// A local class or object is not a callable, so it used to fail the cast below and be silently
		// dropped -- emitting a body that names a type the new function cannot see. It is refused here
		// for the same reason a local `fun` is: only values can be handed over as parameters (R5).
		if (resolved is KaClassSymbol) {
			val classPsi = runCatching { resolved.psi }.getOrNull()
			if (classPsi != null && PsiTreeUtil.isAncestor(enclosing, classPsi, true) && !inRegion(classPsi, span)) {
				return CaptureResult.Refused(ExtractionRefusal.CapturedLocalDeclaration(name))
			}
		}

		val symbol = resolved as? KaCallableSymbol ?: continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull()

		val key: Any =
			when {
				declarationPsi != null -> {
					if (!PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
					if (inRegion(declarationPsi, span)) continue
					declarationPsi
				}

				// `it` has no source PSI, so it would otherwise read as "not captured" and be dropped.
				// Its binding lambda stands in for the missing declaration: captured only when that
				// lambda is outside the region, and keyed on the lambda so that an `it` bound inside the
				// region cannot evict a genuinely captured outer one.
				symbol is KaValueParameterSymbol &&
					name == StandardNames.IMPLICIT_LAMBDA_PARAMETER_NAME.asString() -> {
					val lambda =
						PsiTreeUtil.getParentOfType(reference, KtFunctionLiteral::class.java, true) ?: continue
					if (inRegion(lambda, span)) continue
					lambda
				}

				else -> {
					continue
				}
			}
		if (!seen.add(key)) continue

		// Only a value can be passed. A local `fun`, class or object declared outside the region goes
		// out of scope once the region moves, and handing it over as a parameter of its own return type
		// is not the same program (R5).
		if (symbol !is KaVariableSymbol) {
			return CaptureResult.Refused(ExtractionRefusal.CapturedLocalDeclaration(name))
		}

		// The value survives the move but its type may not: a local class declared inside the enclosing
		// declaration is out of scope at the insertion point, so the parameter could not be written.
		localTypeNameIn(runCatching { symbol.returnType }.getOrNull(), enclosing)?.let {
			return CaptureResult.Refused(ExtractionRefusal.CapturedLocalDeclaration(it))
		}

		val typeText =
			renderedSymbolType(symbol) ?: return CaptureResult.Refused(ExtractionRefusal.UnrenderableType)
		// The signature must print the declared type, but the region may be leaning on a smart cast to
		// something narrower: the declared type breaks the moved body, the narrowed one breaks the call
		// site.
		when (val used = usedTypeOf(reference)) {
			// An intersection (`A & B`) cannot be printed at all, but the declared type just rendered
			// fine, so the two differ and this is a smart cast however it would have been spelled.
			UsedType.Unrenderable -> {
				return CaptureResult.Refused(ExtractionRefusal.SmartCastParameter(name))
			}

			is UsedType.Rendered -> {
				if (used.text != typeText) {
					return CaptureResult.Refused(ExtractionRefusal.SmartCastParameter(name))
				}
			}

			UsedType.Absent -> {
				Unit
			}
		}
		parameters += MethodParameter(name = name, typeText = typeText)
	}
	return CaptureResult.Captured(parameters)
}

/**
 * The type of a reference as the region uses it.
 *
 * [Unrenderable] is kept apart from [Absent] on purpose: folding them together is what let a smart
 * cast to an intersection type pass as "no information" and emit the declared type.
 */
private sealed interface UsedType {
	data object Absent : UsedType

	data object Unrenderable : UsedType

	data class Rendered(
		val text: String,
	) : UsedType
}

private fun KaSession.usedTypeOf(expression: KtExpression): UsedType {
	val type = runCatching { expression.expressionType }.getOrNull() ?: return UsedType.Absent
	return runCatching { typeTextOrNull(type) }.fold(
		onSuccess = { rendered -> rendered?.let { UsedType.Rendered(it) } ?: UsedType.Unrenderable },
		onFailure = { UsedType.Absent },
	)
}

/** Either the derived parameter list or the reason there cannot be one. */
private sealed interface CaptureResult {
	data class Captured(
		val parameters: List<MethodParameter>,
	) : CaptureResult

	data class Refused(
		val refusal: ExtractionRefusal,
	) : CaptureResult
}

private fun KaSession.renderedSymbolType(symbol: KaCallableSymbol): String? =
	runCatching { symbol.returnType }.getOrNull()?.let { renderedTypeTextOrNull(it) }

private fun KaSession.renderedTypeOrNull(expression: KtExpression): String? =
	runCatching { expression.expressionType }.getOrNull()?.let { renderedTypeTextOrNull(it) }

private fun KaSession.renderedDeclarationType(property: KtProperty): String? =
	runCatching { (property.symbol as? KaCallableSymbol)?.returnType }.getOrNull()?.let { renderedTypeTextOrNull(it) }

private fun KaSession.enclosingReturnType(enclosing: KtDeclaration): String? =
	runCatching { (enclosing.symbol as? KaCallableSymbol)?.returnType }.getOrNull()?.let { renderedTypeTextOrNull(it) }

/**
 * What the region declares that the code after it still uses (R7).
 *
 * Every named declaration counts, not just [KtProperty]: a destructuring entry, a local `fun` and a
 * local class are all things the following code can reference, and none of them can be returned.
 * They are collected so [buildCandidate] can refuse them -- omitting them is what produced a call
 * site referring to names that no longer exist.
 *
 * [writtenAfter] is the subset the following code assigns to. The call site emits a `val`, so even a
 * single such output cannot be honoured.
 */
private class RegionOutputs(
	val declarations: List<KtNamedDeclaration>,
	val writtenAfter: List<KtNamedDeclaration>,
) {
	companion object {
		val NONE = RegionOutputs(emptyList(), emptyList())
	}
}

/**
 * "Used after the region" is a textual-offset test inside the enclosing declaration, which is sound
 * because a local is only in scope after its own declaration in the same block.
 */
private fun KaSession.outputsOf(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): RegionOutputs {
	// Lambdas and parameters are named declarations too, and neither can be referenced after the
	// region. Dropping them keeps the short-circuit below meaningful for any region holding a lambda,
	// and keeps a lambda's "<anonymous>" out of a refusal message.
	val declared =
		descendantsOf(elements, KtNamedDeclaration::class.java)
			.filterNot { it is KtFunctionLiteral || it is KtParameter }
	if (declared.isEmpty()) return RegionOutputs.NONE

	val laterReferences =
		PsiTreeUtil
			.collectElementsOfType(enclosing, KtSimpleNameExpression::class.java)
			.filter { it.textRange.startOffset >= span.end }
	val read = laterReferences.filterNot { it.isWriteTarget() }.mapNotNullTo(mutableSetOf()) { resolvedPsi(it) }
	val written = laterReferences.filter { it.isWriteTarget() }.mapNotNullTo(mutableSetOf()) { resolvedPsi(it) }

	return RegionOutputs(
		declarations = declared.filter { it in read || it in written },
		writtenAfter = declared.filter { it in written },
	)
}

private fun KaSession.resolvedPsi(reference: KtSimpleNameExpression): PsiElement? =
	runCatching {
		reference.mainReference
			?.resolveToSymbols()
			?.firstOrNull()
			?.psi
	}.getOrNull()

/**
 * A `var` declared inside the enclosing declaration but outside the region, assigned inside it.
 * Kotlin has no `out` parameters, so the faithful emission would shadow a name (R7, ADR 0013).
 */
private fun KaSession.reassignedOuterVar(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): String? {
	for (reference in simpleNamesIn(elements)) {
		if (!reference.isWriteTarget()) continue
		val symbol =
			runCatching {
				(reference.mainReference?.resolveToSymbols()?.firstOrNull() as? KaVariableSymbol)?.takeIf { !it.isVal }
			}.getOrNull() ?: continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull() ?: continue
		if (!PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
		if (inRegion(declarationPsi, span)) continue
		return reference.getReferencedName()
	}
	return null
}

/**
 * Whether [returnExpression] returns from a function declared *inside* the region, so its jump never
 * crosses the region boundary and it is not an exit (R8).
 *
 * A `KtFunctionLiteral` is skipped rather than accepted: a lambda is transparent to an unlabelled
 * `return`, which targets the enclosing function declaration, so a non-local return out of a lambda in
 * the region really does leave it. An anonymous `fun` is not transparent and is not a literal, so the
 * same walk stops on it correctly.
 */
private fun returnTargetInRegion(
	returnExpression: KtReturnExpression,
	span: TextSpan,
): Boolean {
	var owner = PsiTreeUtil.getParentOfType(returnExpression, KtDeclarationWithBody::class.java, true)
	while (owner is KtFunctionLiteral) {
		owner = PsiTreeUtil.getParentOfType(owner, KtDeclarationWithBody::class.java, true)
	}
	return owner != null && inRegion(owner, span)
}

/**
 * The tail-return exception (R8): the region's last statement is a `return`, and it is the region's
 * only `return`, `break` or `continue`. Purely syntactic, which is why it is worth having.
 */
private fun isTailReturn(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	if (elements.last() !is KtReturnExpression) return false
	val returns =
		descendantsOf(elements, KtReturnExpression::class.java)
			.filterNot { returnTargetInRegion(it, span) }
	if (returns.size != 1 || returns.single() !== elements.last()) return false
	return !hasLoopExit(elements, span)
}

/** Any `return`, `break` or `continue` whose target lies outside the region (R8). */
private fun hasExit(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	for (returnExpression in descendantsOf(elements, KtReturnExpression::class.java)) {
		if (returnTargetInRegion(returnExpression, span)) continue
		// An unlabelled `return` always targets the enclosing named declaration, which is outside the
		// region by construction. A labelled one targets the lambda carrying that label, which is not
		// necessarily the nearest one -- `return@outer` from a nested lambda still leaves the region.
		val label = returnExpression.getLabelName() ?: return true
		val target = labelledLambdaFor(returnExpression, label) ?: return true
		if (!inRegion(target, span)) return true
	}
	return hasLoopExit(elements, span)
}

/** The lambda `return@[label]` targets: the innermost enclosing one carrying that label. */
private fun labelledLambdaFor(
	returnExpression: KtReturnExpression,
	label: String,
): KtFunctionLiteral? {
	var lambda = PsiTreeUtil.getParentOfType(returnExpression, KtFunctionLiteral::class.java, true)
	while (lambda != null) {
		if (lambdaLabel(lambda) == label) return lambda
		lambda = PsiTreeUtil.getParentOfType(lambda, KtFunctionLiteral::class.java, true)
	}
	return null
}

/**
 * The label a `return@` can name this lambda by: its explicit `label@` if it has one, otherwise the
 * name of the function it is an argument to.
 */
private fun lambdaLabel(lambda: KtFunctionLiteral): String? {
	val lambdaExpression = lambda.parent as? KtLambdaExpression ?: return null
	(lambdaExpression.parent as? KtLabeledExpression)?.getLabelName()?.let { return it }
	return callOwning(lambdaExpression)?.calleeName()
}

/** The call [lambdaExpression] is an argument of, trailing or parenthesised. */
private fun callOwning(lambdaExpression: KtLambdaExpression): KtCallExpression? =
	when (val argument = lambdaExpression.parent) {
		is KtLambdaArgument -> argument.parent as? KtCallExpression
		is KtValueArgument -> (argument.parent as? KtValueArgumentList)?.parent as? KtCallExpression
		else -> null
	}

private fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

private fun hasLoopExit(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	val jumps: List<KtExpressionWithLabel> =
		descendantsOf(elements, KtBreakExpression::class.java) +
			descendantsOf(elements, KtContinueExpression::class.java)
	return jumps.any { jump ->
		val loop = targetLoopFor(jump)
		loop == null || !inRegion(loop, span)
	}
}

/**
 * The loop a `break`/`continue` leaves: the innermost enclosing one, or the one its label names.
 *
 * Reading the label matters for the same reason it does for a labelled `return` -- `break@outer` from
 * a nested loop inside the region leaves the region, however local the nearest loop looks.
 */
private fun targetLoopFor(jump: KtExpressionWithLabel): KtLoopExpression? {
	var loop = PsiTreeUtil.getParentOfType(jump, KtLoopExpression::class.java, true)
	val label = jump.getLabelName() ?: return loop
	while (loop != null) {
		if ((loop.parent as? KtLabeledExpression)?.getLabelName() == label) return loop
		loop = PsiTreeUtil.getParentOfType(loop, KtLoopExpression::class.java, true)
	}
	return null
}

/** An accessor's type parameters live on its property, the same place its receiver does. */
private fun typeParameterNamesOf(enclosing: KtDeclaration): List<String> =
	when (enclosing) {
		is KtNamedFunction -> enclosing.typeParameters.mapNotNull { it.name }
		is KtPropertyAccessor -> enclosing.property.typeParameters.mapNotNull { it.name }
		else -> emptyList()
	}

/**
 * The name of the enclosing function's type parameter the region *writes out*, or null. A filtered
 * copy of the type-parameter list with its bounds is the alternative, and deciding "is `T`
 * referenced" from rendered type text is exactly the fragility that rules it out (R10).
 *
 * This catches only a type the region names. A type argument the region gets by inference names
 * nothing at all, and is caught by [renderedTypeParameterIn] once the signature exists.
 */
private fun typeParameterIn(
	names: List<String>,
	elements: List<KtExpression>,
): String? {
	if (names.isEmpty()) return null

	val typeTexts =
		descendantsOf(elements, KtTypeReference::class.java).map { it.text } +
			simpleNamesIn(elements).map { it.getReferencedName() }
	return names.firstOrNull { name -> typeTexts.any { it == name || it.containsWord(name) } }
}

/**
 * The type parameter that leaked into the derived signature, or null.
 *
 * `fun <T> demo(a: T, b: T) { pick(a, b) }` names `T` nowhere in the region, but the parameters
 * render as `T` -- and the new function has no type-parameter list to bind it. Checking the rendered
 * strings is the only place that shows up before the text is emitted.
 */
private fun renderedTypeParameterIn(
	names: List<String>,
	renderedTypes: List<String>,
): String? {
	if (names.isEmpty()) return null
	return names.firstOrNull { name -> renderedTypes.any { it == name || it.containsWord(name) } }
}

/** Whole-word containment, so `T` does not match `Type`. */
private fun String.containsWord(word: String): Boolean =
	Regex("(^|[^A-Za-z0-9_])" + Regex.escape(word) + "($|[^A-Za-z0-9_])").containsMatchIn(this)

/**
 * Whether the region reads or writes a property accessor's backing field (R4).
 *
 * `field` is in scope only inside the accessor, so it would move verbatim into the new function and
 * stop resolving. Gated on the enclosing declaration being an accessor, which costs nothing
 * everywhere else, and confirmed against the resolved symbol so a local that happens to be called
 * `field` is not mistaken for it.
 */
private fun KaSession.usesBackingField(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
): Boolean {
	if (enclosing !is KtPropertyAccessor) return false
	return simpleNamesIn(elements).any { reference ->
		reference.getReferencedName() == BACKING_FIELD_NAME &&
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() is KaBackingFieldSymbol
	}
}

/**
 * The scoping construct whose implicit receiver the region uses unqualified, or null (R9).
 *
 * Turning that receiver into a parameter would mean qualifying every unqualified member access
 * inside the extracted body -- editing the interior of the moved code, which this refactoring does
 * not do. Android code leans on `with`/`apply` heavily, so the message names the construct.
 *
 * The question is asked of the resolved call rather than of a list of known scoping-function names:
 * a name list both over-refuses (an inherited member or an outer-class member reached with no
 * qualifier is not the receiver's) and under-refuses (it cannot know about `coroutineScope`,
 * `buildAnnotatedString`, or any Compose scope). A receiver that is implicit and belongs to a lambda
 * between the region and the enclosing declaration is exactly what does not survive the move.
 */
private fun KaSession.innerImplicitReceiver(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): String? {
	for (reference in simpleNamesIn(elements)) {
		// A qualified selector already has its receiver written out next to it. Deliberately syntactic
		// and deliberately shallow: a *call* selector (`h.doubled()`) must NOT be skipped, because its
		// dispatch receiver can still be an implicit one -- a member extension invoked on a `with`
		// receiver is the pervasive Compose shape (`with(density) { size.toPx() }`).
		val parent = reference.parent
		if (parent is KtQualifiedExpression && parent.selectorExpression === reference) continue

		val lambda = implicitReceiverLambdaFor(reference) ?: continue
		if (isBoundOutsideRegion(enclosing, lambda, span)) return constructNameFor(lambda)
	}

	// A bare `this` names the receiver without going through a call, so no resolved call reports it.
	// Left undetected it does not fail to compile -- it silently becomes the enclosing class instance,
	// which is worse.
	for (thisExpression in descendantsOf(elements, KtThisExpression::class.java)) {
		val symbol =
			runCatching {
				thisExpression.instanceReference.mainReference
					?.resolveToSymbols()
					?.firstOrNull()
			}.getOrNull()
		val lambda = lambdaOwning(symbol) ?: continue
		if (isBoundOutsideRegion(enclosing, lambda, span)) return constructNameFor(lambda)
	}
	return null
}

/** Whether [lambda] binds its receiver between the region and [enclosing], so the move loses it. */
private fun isBoundOutsideRegion(
	enclosing: KtDeclaration,
	lambda: KtFunctionLiteral,
	span: TextSpan,
): Boolean = !inRegion(lambda, span) && PsiTreeUtil.isAncestor(enclosing, lambda, true)

private fun constructNameFor(lambda: KtFunctionLiteral): String =
	(lambda.parent as? KtLambdaExpression)?.let { callOwning(it)?.calleeName() } ?: UNNAMED_SCOPING_CONSTRUCT

/**
 * The lambda supplying [reference]'s implicit receiver, or null when it has none or the receiver
 * comes from somewhere that survives the move (a class, the enclosing function's own receiver).
 */
private fun KaSession.implicitReceiverLambdaFor(reference: KtSimpleNameExpression): KtFunctionLiteral? =
	runCatching {
		// A callee name does not resolve to a call on its own; its call expression does.
		val callSource =
			(reference.parent as? KtCallExpression)?.takeIf { it.calleeExpression === reference } ?: reference
		val call = callSource.resolveToCall()
		// Defensive only. A compound assignment (`n += 1` inside `apply { }`) redirects to the whole
		// compound access, but the resolver flags that redirect and still hands back a plain variable
		// access, so the branch above already catches it in this version.
		val applied =
			call?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol
				?: call?.successfulCallOrNull<KaCompoundVariableAccessCall>()?.variableCall?.partiallyAppliedSymbol
				?: call?.successfulCallOrNull<KaCompoundArrayAccessCall>()?.getterCall?.partiallyAppliedSymbol
		receiverLambda(applied?.dispatchReceiver) ?: receiverLambda(applied?.extensionReceiver)
	}.getOrNull()

private fun receiverLambda(receiver: KaReceiverValue?): KtFunctionLiteral? = lambdaOwning((receiver as? KaImplicitReceiverValue)?.symbol)

/** The lambda [symbol] belongs to, when it is a lambda's receiver rather than a class's. */
private fun lambdaOwning(symbol: KaSymbol?): KtFunctionLiteral? {
	if (symbol == null) return null
	// A lambda's receiver reports itself either as the anonymous function or as that function's
	// receiver parameter, and only the former carries the PSI.
	val psi =
		runCatching { symbol.psi }.getOrNull()
			?: runCatching { (symbol as? KaReceiverParameterSymbol)?.owningCallableSymbol?.psi }.getOrNull()
			?: return null
	return psi as? KtFunctionLiteral ?: (psi as? KtLambdaExpression)?.functionLiteral
}

/**
 * The receiver the new function must repeat, or null (R4).
 *
 * An accessor's receiver is declared on its property (`val Foo.x get() = ...`), not on the accessor,
 * so reading only the accessor drops it and the moved body's unqualified members stop resolving.
 */
private fun receiverTypeTextOf(enclosing: KtDeclaration): String? =
	when (enclosing) {
		is KtNamedFunction -> enclosing.receiverTypeReference?.text
		is KtPropertyAccessor -> enclosing.property.receiverTypeReference?.text
		else -> null
	}

/**
 * `suspend` is added when the region calls one, or touches `coroutineContext` (R10).
 *
 * A suspension the region only performs inside a *nested* suspend-typed lambda does not count: the
 * region carries that lambda with it, so the new function needs no `suspend`, and adding it breaks a
 * call site that is not itself a suspend context. `scope.launch { }` and `runBlocking { }` are that
 * shape, and "extract this whole launch block" is an everyday request.
 */
private fun KaSession.usesSuspend(elements: List<KtExpression>): Boolean =
	elements.any { root ->
		PsiTreeUtil
			.collectElementsOfType(root, KtSimpleNameExpression::class.java)
			.any { it.getReferencedName() == COROUTINE_CONTEXT_NAME && !inNestedSuspendLambda(it, root) } ||
			PsiTreeUtil
				.collectElementsOfType(root, KtCallExpression::class.java)
				.any { isSuspendCall(it) && !inNestedSuspendLambda(it, root) }
	}

private fun KaSession.isSuspendCall(call: KtCallExpression): Boolean =
	runCatching {
		(call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol as? KaNamedFunctionSymbol)?.isSuspend
	}.getOrNull() == true

/**
 * Whether [element] sits inside a suspend-typed lambda that is itself inside [root].
 *
 * An ordinary inline lambda -- `forEach`, `let`, `run` -- is not one, so a suspension inside it still
 * propagates `suspend` outwards, which is correct: those bodies run in the caller's context.
 */
private fun KaSession.inNestedSuspendLambda(
	element: PsiElement,
	root: PsiElement,
): Boolean {
	// Strict ancestors of [element] that are strict descendants of [root]. A lambda *containing* the
	// region is not one of these: the region moves out of it, so the suspension is the new function's.
	var current: PsiElement? = element.takeIf { it !== root }?.parent
	while (current != null && current !== root) {
		if (current is KtFunctionLiteral && isSuspendLambda(current)) return true
		current = current.parent
	}
	return false
}

/**
 * Read off the lambda expression's own functional type rather than its symbol: the anonymous-function
 * symbol in this Analysis API build carries no `suspend`, while the type inferred from the parameter
 * it is passed to does.
 */
private fun KaSession.isSuspendLambda(lambda: KtFunctionLiteral): Boolean =
	runCatching {
		((lambda.parent as? KtLambdaExpression)?.expressionType as? KaFunctionType)?.isSuspend
	}.getOrNull() == true

/**
 * `@Composable` is added when the region uses one. Not polish: CoGo users write Compose apps on the
 * device, and an extracted composable without the annotation does not compile (R10).
 *
 * Property *getters* count, not only calls. `MaterialTheme.colorScheme` and `LocalDensity.current` are
 * annotated getters reached through a name reference, and they are as common in Compose code as any
 * composable call.
 */
private fun KaSession.usesComposable(elements: List<KtExpression>): Boolean =
	descendantsOf(elements, KtCallExpression::class.java).any { call ->
		runCatching {
			call
				.resolveToCall()
				?.successfulFunctionCallOrNull()
				?.symbol
				?.hasComposableAnnotation()
		}.getOrNull() == true
	} ||
		simpleNamesIn(elements).any { reference ->
			runCatching {
				val property = reference.mainReference.resolveToSymbols().firstOrNull() as? KaPropertySymbol
				property?.getter?.hasComposableAnnotation() == true
			}.getOrNull() == true
		}

/** Whether [this] carries `@Composable`. */
private fun KaAnnotatedSymbol.hasComposableAnnotation(): Boolean =
	annotations.any { it.classId?.asFqNameString() == COMPOSABLE_FQ_NAME }

/**
 * Names the new function must avoid (R12).
 *
 * [isLocalTarget] is tested first, and must be: a local `fun` inside a class member competes with the
 * enclosing block's declarations, not with the class's members, and validating against the class
 * instead lets the new local collide with a sibling local -- a redeclaration error.
 *
 * For a class target this is the whole member scope, **including inherited members**: a private
 * function accidentally matching a supertype member is an accidental-override compile error.
 * Rejecting any name match rather than only a signature match also means the refactoring never
 * creates an overload the user did not ask for.
 */
private fun KaSession.takenNamesFor(
	enclosing: KtDeclaration,
	anchor: KtDeclaration,
	isLocalTarget: Boolean,
): Set<String> {
	if (isLocalTarget) {
		return PsiTreeUtil
			.collectElementsOfType(anchor.parent, KtDeclaration::class.java)
			.mapNotNull { it.name }
			.toSet()
	}

	val containingClass = PsiTreeUtil.getParentOfType(enclosing, KtClassOrObject::class.java, true)
	if (containingClass != null) {
		val fromScope =
			runCatching {
				(containingClass.symbol as? KaClassSymbol)
					?.memberScope
					?.callables
					?.mapNotNull { (it as? KaNamedSymbol)?.name?.asString() }
					?.toSet()
			}.getOrNull().orEmpty()
		val declared = containingClass.declarations.mapNotNull { it.name }
		return fromScope + declared
	}

	return enclosing.containingKtFile.declarations
		.mapNotNull { it.name }
		.toSet()
}
