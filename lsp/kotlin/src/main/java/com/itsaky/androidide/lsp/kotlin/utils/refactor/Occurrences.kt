package com.itsaky.androidide.lsp.kotlin.utils.refactor

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtUnaryExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Whether [a] and [b] are the same expression for extraction purposes: structurally identical *and*
 * every name reference in them resolving to the same declaration.
 *
 * The symbol check is the whole point. Text or structure alone would happily match `config.timeout`
 * inside a nested lambda where `config` is a different `config`, or an `it` that means something
 * else -- replacing those would silently change behaviour. The parent ticket (ADFA-3324) states the
 * standard outright: text-based matching breaks things.
 */
internal fun KaSession.isSameExpression(
	a: PsiElement,
	b: PsiElement,
): Boolean {
	if (a === b) return true
	if (a.node?.elementType != b.node?.elementType) return false

	if (a is KtSimpleNameExpression && b is KtSimpleNameExpression) {
		if (a.getReferencedName() != b.getReferencedName()) return false
		if (!resolvesToSameDeclaration(a, b)) return false
	}

	val childrenA = meaningfulChildren(a)
	val childrenB = meaningfulChildren(b)
	if (childrenA.size != childrenB.size) return false
	if (childrenA.isEmpty()) return a.text == b.text
	return childrenA.indices.all { isSameExpression(childrenA[it], childrenB[it]) }
}

/** Whitespace and comments are formatting, not structure, so they never affect equality. */
private fun meaningfulChildren(element: PsiElement): List<PsiElement> =
	element.children.filter { it !is PsiWhiteSpace && it !is PsiComment }

/**
 * Whether two same-named references point at the same declaration.
 *
 * Source declarations are compared by PSI identity, which is exactly the question being asked ("the
 * same `val`?"). Symbols without source PSI -- library members, compiler-generated declarations --
 * fall back to symbol equality. Resolution over broken code throws, and a throw here must read as
 * "not the same" rather than crash the action.
 */
private fun KaSession.resolvesToSameDeclaration(
	a: KtSimpleNameExpression,
	b: KtSimpleNameExpression,
): Boolean =
	runCatching {
		val symbolA = a.mainReference?.resolveToSymbols()?.firstOrNull() ?: return false
		val symbolB = b.mainReference?.resolveToSymbols()?.firstOrNull() ?: return false
		val psiA = symbolA.declarationPsi()
		val psiB = symbolB.declarationPsi()
		if (psiA != null || psiB != null) psiA === psiB else symbolA == symbolB
	}.getOrDefault(false)

private fun KaSymbol.declarationPsi(): PsiElement? = runCatching { psi }.getOrNull()

/**
 * Every site in [searchRoot] within [searchRange] that is the same expression as [candidate] and is
 * itself a legal place to put the variable reference.
 *
 * The legality filter matters: in `a.a`, a candidate of `a` matches the selector too, but rewriting
 * a selector would produce `v.v`. Overlapping matches are dropped so no site is rewritten twice.
 * Ascending by offset, and always contains [candidate] itself.
 */
internal fun KaSession.findOccurrences(
	candidate: KtExpression,
	searchRoot: PsiElement,
	searchRange: TextSpan,
): List<TextSpan> {
	val elementType = candidate.node?.elementType
	val matches =
		PsiTreeUtil
			.collectElements(searchRoot) { element ->
				element.node?.elementType == elementType &&
					element is KtExpression &&
					element.textRange.startOffset >= searchRange.start &&
					element.textRange.endOffset <= searchRange.end
			}.filterIsInstance<KtExpression>()
			.filter { it === candidate || (it.isLegalExtractionTarget() && isSameExpression(candidate, it)) }
			.map { TextSpan(it.textRange.startOffset, it.textRange.endOffset) }
			.sortedBy { it.start }

	val accepted = mutableListOf<TextSpan>()
	for (match in matches) {
		if (accepted.none { it.overlaps(match) }) accepted += match
	}
	return accepted
}

/**
 * The innermost scope that must contain the declaration, or null when the candidate references
 * nothing declared inside the enclosing scopes.
 *
 * This is what stops a hoist from escaping a lambda it depends on: if the candidate uses `it` or a
 * lambda parameter, that lambda's body comes back as the ceiling and every outer rung of the scope
 * chain is dropped by [truncateAtCeiling].
 */
internal fun KaSession.referencedDeclarationCeiling(candidate: KtExpression): PsiElement? {
	var deepest: PsiElement? = null
	var deepestDepth = -1
	for (reference in candidate.collectDescendantsOfType<KtSimpleNameExpression>()) {
		val symbol = runCatching { reference.mainReference?.resolveToSymbols()?.firstOrNull() }.getOrNull() ?: continue
		val body = constrainingBodyFor(reference, symbol) ?: continue
		val depth = depthOf(body)
		if (depth > deepestDepth) {
			deepest = body
			deepestDepth = depth
		}
	}
	return deepest
}

/**
 * The scope [reference] pins the declaration inside, or null when it constrains nothing.
 *
 * A declaration outside the candidate's own scopes -- a class member, a top-level property, anything
 * from a library -- constrains nothing; only locals and parameters do.
 *
 * The implicit lambda parameter needs its own case: `it` has **no source PSI**, so the ordinary
 * psi-based lookup finds nothing and would report "unconstrained", happily hoisting `it.length` clean
 * out of its lambda into code that does not compile. A value-parameter symbol with no PSI, referenced
 * by the name `it`, *is* by definition the implicit parameter of the innermost enclosing lambda -- a
 * property of the language, not a guess about the text.
 */
private fun constrainingBodyFor(
	reference: KtSimpleNameExpression,
	symbol: KaSymbol,
): PsiElement? {
	val declaration = runCatching { symbol.psi }.getOrNull()
	if (declaration == null) {
		if (symbol is KaValueParameterSymbol && reference.getReferencedName() == StandardNames.IMPLICIT_LAMBDA_PARAMETER_NAME.asString()) {
			return PsiTreeUtil.getParentOfType(reference, KtFunctionLiteral::class.java, true)?.bodyExpression
		}
		return null
	}
	if (!PsiTreeUtil.isAncestor(reference.containingFile, declaration, false)) return null
	return enclosingExecutableBody(declaration)
}

private fun depthOf(element: PsiElement): Int = element.parents.count()

private inline fun <reified T : PsiElement> PsiElement.collectDescendantsOfType(): List<T> =
	PsiTreeUtil.collectElementsOfType(this, T::class.java).toList()

/**
 * Restricts [occurrences] to a contiguous run around [candidateSpan] that no write to a referenced
 * mutable interrupts.
 *
 * A `var` the candidate reads can be reassigned between two occurrences, and then the two sites do
 * not hold the same value even though they are the same expression:
 *
 * ```
 * var limit = 1
 * foo(limit + 1)   // occurrence
 * limit = 5
 * foo(limit + 1)   // same expression, different value
 * ```
 *
 * Rather than warn, unsound sites are simply excluded, so "Replace all N occurrences" can never
 * produce wrong code and N is always achievable. The walk grows outwards from the candidate -- never
 * dropping the site the user actually selected -- and stops in each direction at the first write it
 * would have to cross.
 */
internal fun excludeUnsoundOccurrences(
	occurrences: List<TextSpan>,
	candidateSpan: TextSpan,
	writeOffsets: List<Int>,
): List<TextSpan> {
	if (occurrences.isEmpty()) return occurrences
	val ordered = occurrences.sortedBy { it.start }
	val candidateIndex = ordered.indexOfFirst { it.start == candidateSpan.start && it.end == candidateSpan.end }
	if (candidateIndex < 0) return listOf(candidateSpan)

	val writes = writeOffsets.sorted()

	fun writeBetween(
		from: Int,
		to: Int,
	): Boolean = writes.any { it in from until to }

	val accepted = mutableListOf(ordered[candidateIndex])
	for (i in candidateIndex - 1 downTo 0) {
		if (writeBetween(ordered[i].end, ordered[candidateIndex].start)) break
		accepted.add(0, ordered[i])
	}
	for (i in candidateIndex + 1 until ordered.size) {
		if (writeBetween(ordered[candidateIndex].end, ordered[i].start)) break
		accepted += ordered[i]
	}
	return accepted
}

/**
 * Offsets of writes, within [searchRoot], to any mutable the candidate reads. Feeds
 * [excludeUnsoundOccurrences].
 *
 * Counts plain assignment, the augmented forms (`+=` and friends) and `++`/`--`. A `val` cannot be
 * written, so only [KaVariableSymbol]s that report themselves mutable are tracked.
 */
internal fun KaSession.writeOffsetsFor(
	candidate: KtExpression,
	searchRoot: PsiElement,
): List<Int> {
	val mutableDeclarations =
		candidate
			.collectDescendantsOfType<KtSimpleNameExpression>()
			.mapNotNull { reference ->
				runCatching {
					(reference.mainReference?.resolveToSymbols()?.firstOrNull() as? KaVariableSymbol)
						?.takeIf { !it.isVal }
						?.psi
				}.getOrNull()
			}.toSet()
	if (mutableDeclarations.isEmpty()) return emptyList()

	return searchRoot
		.collectDescendantsOfType<KtSimpleNameExpression>()
		.filter { it.isWriteTarget() }
		.filter { reference ->
			runCatching {
				reference.mainReference
					?.resolveToSymbols()
					?.firstOrNull()
					?.psi
			}.getOrNull() in mutableDeclarations
		}.map { it.textRange.startOffset }
}

/** Whether this reference is being written to rather than read. */
private fun KtSimpleNameExpression.isWriteTarget(): Boolean {
	val parent = parent
	if (parent is KtBinaryExpression && parent.left === this && parent.operationToken in ASSIGNMENT_TOKENS) return true
	if (parent is KtUnaryExpression && parent.operationToken in INCREMENT_TOKENS) return true
	return false
}

private val ASSIGNMENT_TOKENS =
	setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

private val INCREMENT_TOKENS = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)

/**
 * Names a suggestion must avoid: every declaration name in the file.
 *
 * Deliberately conservative rather than scope-exact. A real scope query would need resolution and
 * would let `size` be suggested in one function because the collision is in another -- correct, but
 * the cost of being over-broad is only a `size1` where `size` would have done, while the cost of
 * being under-broad is generated code that shadows something. Cheap, needs no analysis, and being
 * purely syntactic it is unit-testable.
 */
internal fun visibleNamesAt(candidate: KtExpression): Set<String> =
	PsiTreeUtil
		.collectElementsOfType(candidate.containingFile, KtDeclaration::class.java)
		.mapNotNullTo(mutableSetOf()) { it.name }
