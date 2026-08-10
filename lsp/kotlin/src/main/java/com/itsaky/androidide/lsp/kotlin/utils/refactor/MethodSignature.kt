package com.itsaky.androidide.lsp.kotlin.utils.refactor

import com.itsaky.androidide.lsp.kotlin.utils.renderName
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
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
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTypeReference

/** The name of the statement-range suggestion; there is no expression to read a name from (R12). */
private const val STATEMENT_RANGE_NAME = "extracted"

private const val COMPOSABLE_FQ_NAME = "androidx.compose.runtime.Composable"

/**
 * Receiver-binding scoping functions. `let`, `also` and `forEach` are absent on purpose: they bind
 * `it`, which is a captured declaration and becomes an ordinary parameter (R5).
 */
private val RECEIVER_SCOPING_FUNCTIONS =
	setOf("with", "apply", "run", "buildString", "buildList", "buildMap", "buildSet")

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

	typeParameterIn(enclosing, elements)?.let { return refuse(ExtractionRefusal.UsesTypeParameter(it)) }
	innerImplicitReceiver(enclosing, elements, span)?.let { return refuse(ExtractionRefusal.InnerImplicitReceiver(it)) }
	reassignedOuterVar(enclosing, elements, span)?.let { return refuse(ExtractionRefusal.ReassignsOuterVar(it)) }

	val tailReturn = !isExpression && isTailReturn(elements, span)
	if (!tailReturn && hasExit(elements, span)) return refuse(ExtractionRefusal.ExitsRegion)

	val outputs = if (isExpression) emptyList() else outputsOf(enclosing, elements, span)
	if (outputs.size > 1) {
		return refuse(ExtractionRefusal.MultipleOutputs(outputs.mapNotNull { it.name }))
	}
	// The tail-return exception holds only when nothing else flows out (R8).
	if (tailReturn && outputs.isNotEmpty()) return refuse(ExtractionRefusal.ExitsRegion)

	val parameters = capturedParameters(enclosing, elements, span) ?: return refuse(ExtractionRefusal.UnrenderableType)

	val returnTypeText =
		when {
			isExpression -> {
				renderedTypeOrNull(first) ?: return refuse(ExtractionRefusal.UnrenderableType)
			}

			tailReturn -> {
				enclosingReturnType(enclosing) ?: return refuse(ExtractionRefusal.UnrenderableType)
			}

			outputs.size == 1 -> {
				renderedDeclarationType(outputs.single()) ?: return refuse(ExtractionRefusal.UnrenderableType)
			}

			else -> {
				null
			}
		}.takeUnless { it == "Unit" }

	val body =
		when {
			isExpression -> ExtractedBody.ExpressionBody(needsReturn = returnTypeText != null)
			outputs.size == 1 -> ExtractedBody.StatementBody(trailingReturn = "return ${outputs.single().name.orEmpty()}")
			else -> ExtractedBody.StatementBody(trailingReturn = null)
		}

	val callSite =
		when {
			tailReturn -> CallSiteForm.Return
			outputs.size == 1 -> CallSiteForm.AssignOutput(outputs.single().name.orEmpty())
			else -> CallSiteForm.Call
		}

	val takenNames = takenNamesFor(enclosing)

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
			modifiers = if (usesSuspend(elements)) listOf("private", "suspend") else listOf("private"),
			receiverTypeText = (enclosing as? KtNamedFunction)?.receiverTypeReference?.text,
			parameters = parameters,
			returnTypeText = returnTypeText,
			body = body,
			callSite = callSite,
			insertOffset = enclosing.textRange.endOffset,
			insertIndent = leadingIndentAt(fileText, enclosing.textRange.startOffset),
		),
	)
}

private fun refuse(refusal: ExtractionRefusal): SignatureResult = SignatureResult.Refused(refusal)

/**
 * The named function, accessor, `init` block or constructor whose body holds [element]. Lambdas are
 * skipped: the new function is a sibling of the enclosing *named* declaration (R4), and the lambda's
 * captures become parameters.
 */
private fun enclosingDeclaration(element: PsiElement): KtDeclaration? {
	var current: PsiElement? = element.parent
	while (current != null) {
		when (current) {
			is KtNamedFunction, is KtPropertyAccessor, is KtAnonymousInitializer, is KtSecondaryConstructor -> {
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
 * A captured declaration is one the region references whose PSI lies inside the enclosing
 * declaration but outside the region itself. Anything else -- a class member, a top-level
 * declaration, an import -- resolves unchanged from the new function's body (R5).
 *
 * Returns null when a type cannot be rendered as source, which declines the extraction rather than
 * emitting text that will not compile.
 */
private fun KaSession.capturedParameters(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): List<MethodParameter>? {
	val parameters = mutableListOf<MethodParameter>()
	val seen = mutableSetOf<Any>()

	for (reference in simpleNamesIn(elements).sortedBy { it.textRange.startOffset }) {
		val symbol =
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() as? KaCallableSymbol
				?: continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull()

		val key: Any =
			when {
				declarationPsi != null -> {
					if (!PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
					if (inRegion(declarationPsi, span)) continue
					declarationPsi
				}

				// `it` has no source PSI, so it would otherwise read as "not captured" and be dropped.
				symbol is KaValueParameterSymbol &&
					reference.getReferencedName() == StandardNames.IMPLICIT_LAMBDA_PARAMETER_NAME.asString() -> {
					"it"
				}

				else -> {
					continue
				}
			}
		if (!seen.add(key)) continue

		val typeText = renderedSymbolType(symbol) ?: return null
		parameters += MethodParameter(name = reference.getReferencedName(), typeText = typeText)
	}
	return parameters
}

/** A type that cannot be written out as source -- anonymous, intersection, or a resolution error. */
private fun isUnrenderable(text: String): Boolean =
	text.isBlank() ||
		text.contains("anonymous") ||
		text.contains("ERROR") ||
		text.contains(" & ")

@OptIn(KaExperimentalApi::class)
private fun KaSession.renderedSymbolType(symbol: KaCallableSymbol): String? =
	runCatching { renderName(symbol.returnType) }.getOrNull()?.takeUnless(::isUnrenderable)

@OptIn(KaExperimentalApi::class)
private fun KaSession.renderedTypeOrNull(expression: KtExpression): String? =
	runCatching { expression.expressionType?.let { renderName(it) } }.getOrNull()?.takeUnless(::isUnrenderable)

@OptIn(KaExperimentalApi::class)
private fun KaSession.renderedDeclarationType(property: KtProperty): String? =
	runCatching { (property.symbol as? KaCallableSymbol)?.returnType?.let { renderName(it) } }
		.getOrNull()
		?.takeUnless(::isUnrenderable)

@OptIn(KaExperimentalApi::class)
private fun KaSession.enclosingReturnType(enclosing: KtDeclaration): String? =
	runCatching { (enclosing.symbol as? KaCallableSymbol)?.returnType?.let { renderName(it) } }
		.getOrNull()
		?.takeUnless(::isUnrenderable)

/**
 * Locals declared inside the region and read after it (R7). Exactly one is supported.
 *
 * "Read after it" is a textual-offset test inside the enclosing declaration, which is sound because
 * a local is only in scope after its own declaration in the same block.
 */
private fun KaSession.outputsOf(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): List<KtProperty> {
	val declared = descendantsOf(elements, KtProperty::class.java)
	if (declared.isEmpty()) return emptyList()

	val laterReads =
		PsiTreeUtil
			.collectElementsOfType(enclosing, KtSimpleNameExpression::class.java)
			.filter { it.textRange.startOffset >= span.end }
			.mapNotNull {
				runCatching {
					it.mainReference
						?.resolveToSymbols()
						?.firstOrNull()
						?.psi
				}.getOrNull()
			}.toSet()

	return declared.filter { it in laterReads }
}

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
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() as? KaVariableSymbol
				?: continue
		if (symbol.isVal) continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull() ?: continue
		if (!PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
		if (inRegion(declarationPsi, span)) continue
		return reference.getReferencedName()
	}
	return null
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
	val returns = descendantsOf(elements, KtReturnExpression::class.java)
	if (returns.size != 1 || returns.single() !== elements.last()) return false
	return !hasLoopExit(elements, span)
}

/** Any `return`, `break` or `continue` whose target lies outside the region (R8). */
private fun hasExit(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	for (returnExpression in descendantsOf(elements, KtReturnExpression::class.java)) {
		// An unlabelled `return` always targets the enclosing named declaration, which is outside the
		// region by construction. A labelled one is fine only when its lambda is inside the region.
		if (returnExpression.getLabelName() == null) return true
		val lambda = PsiTreeUtil.getParentOfType(returnExpression, KtFunctionLiteral::class.java, true)
		if (lambda == null || !inRegion(lambda, span)) return true
	}
	return hasLoopExit(elements, span)
}

private fun hasLoopExit(
	elements: List<KtExpression>,
	span: TextSpan,
): Boolean {
	val jumps =
		descendantsOf(elements, KtBreakExpression::class.java) +
			descendantsOf(elements, KtContinueExpression::class.java)
	return jumps.any { jump ->
		val loop = PsiTreeUtil.getParentOfType(jump, KtLoopExpression::class.java, true)
		loop == null || !inRegion(loop, span)
	}
}

/**
 * The name of the enclosing function's type parameter the region uses, or null. A filtered copy of
 * the type-parameter list with its bounds is the alternative, and deciding "is `T` referenced" from
 * rendered type text is exactly the fragility that rules it out (R10).
 */
private fun typeParameterIn(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
): String? {
	val names = (enclosing as? KtNamedFunction)?.typeParameters?.mapNotNull { it.name }.orEmpty()
	if (names.isEmpty()) return null

	val typeTexts =
		descendantsOf(elements, KtTypeReference::class.java).map { it.text } +
			simpleNamesIn(elements).map { it.getReferencedName() }
	return names.firstOrNull { name -> typeTexts.any { it == name || it.containsWord(name) } }
}

/** Whole-word containment, so `T` does not match `Type`. */
private fun String.containsWord(word: String): Boolean =
	Regex("(^|[^A-Za-z0-9_])" + Regex.escape(word) + "($|[^A-Za-z0-9_])").containsMatchIn(this)

/**
 * The scoping construct whose implicit receiver the region uses unqualified, or null (R9).
 *
 * Turning that receiver into a parameter would mean qualifying every unqualified member access
 * inside the extracted body -- editing the interior of the moved code, which this refactoring does
 * not do. Android code leans on `with`/`apply` heavily, so the message names the construct.
 */
private fun KaSession.innerImplicitReceiver(
	enclosing: KtDeclaration,
	elements: List<KtExpression>,
	span: TextSpan,
): String? {
	val construct = enclosingScopingCall(elements.first(), enclosing) ?: return null
	val enclosingClass = PsiTreeUtil.getParentOfType(enclosing, KtClassOrObject::class.java, true)

	for (reference in simpleNamesIn(elements)) {
		val parent = reference.parent
		if (parent is KtQualifiedExpression && parent.selectorExpression === reference) continue
		if (parent is KtCallExpression && parent.calleeExpression !== reference) continue

		val symbol =
			runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() as? KaCallableSymbol
				?: continue
		val declarationPsi = runCatching { symbol.psi }.getOrNull() ?: continue

		// A local or a member of the class the new function joins needs nothing.
		if (PsiTreeUtil.isAncestor(enclosing, declarationPsi, true)) continue
		if (enclosingClass != null && PsiTreeUtil.isAncestor(enclosingClass, declarationPsi, true)) continue
		// A top-level declaration resolves unchanged from anywhere in the file.
		if (declarationPsi.parent is KtFile) continue
		// Anything else reached without a qualifier came in through the scoping receiver.
		if (inRegion(declarationPsi, span)) continue
		return construct
	}
	return null
}

/** The callee name of the nearest receiver-binding scoping call between [element] and [enclosing]. */
private fun enclosingScopingCall(
	element: PsiElement,
	enclosing: KtDeclaration,
): String? {
	var current: PsiElement? = element
	while (current != null && current !== enclosing) {
		if (current is KtFunctionLiteral) {
			val call = PsiTreeUtil.getParentOfType(current, KtCallExpression::class.java, true)
			val callee = (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
			if (callee != null && callee in RECEIVER_SCOPING_FUNCTIONS) return callee
		}
		current = current.parent
	}
	return null
}

/** `suspend` is added when the region calls one, or touches `coroutineContext` (R10). */
private fun KaSession.usesSuspend(elements: List<KtExpression>): Boolean {
	if (simpleNamesIn(elements).any { it.getReferencedName() == "coroutineContext" }) return true
	return descendantsOf(elements, KtCallExpression::class.java).any { call ->
		runCatching {
			(call.resolveToCall()?.successfulFunctionCallOrNull()?.symbol as? KaNamedFunctionSymbol)?.isSuspend
		}.getOrNull() == true
	}
}

/**
 * `@Composable` is added when the region calls one. Not polish: CoGo users write Compose apps on the
 * device, and an extracted composable without the annotation does not compile (R10).
 */
private fun KaSession.usesComposable(elements: List<KtExpression>): Boolean =
	descendantsOf(elements, KtCallExpression::class.java).any { call ->
		runCatching {
			call
				.resolveToCall()
				?.successfulFunctionCallOrNull()
				?.symbol
				?.annotations
				?.any { it.classId?.asFqNameString() == COMPOSABLE_FQ_NAME }
		}.getOrNull() == true
	}

/**
 * Names the new function must avoid (R12).
 *
 * For a class target this is the whole member scope, **including inherited members**: a private
 * function accidentally matching a supertype member is an accidental-override compile error.
 * Rejecting any name match rather than only a signature match also means the refactoring never
 * creates an overload the user did not ask for.
 */
private fun KaSession.takenNamesFor(enclosing: KtDeclaration): Set<String> {
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

	// A local `fun` target: the enclosing block's own declarations. Otherwise the file's top level.
	val block = enclosing.parent
	if (block is KtBlockExpression) {
		return PsiTreeUtil
			.collectElementsOfType(block, KtDeclaration::class.java)
			.mapNotNull { it.name }
			.toSet()
	}
	return enclosing.containingKtFile.declarations
		.mapNotNull { it.name }
		.toSet()
}
