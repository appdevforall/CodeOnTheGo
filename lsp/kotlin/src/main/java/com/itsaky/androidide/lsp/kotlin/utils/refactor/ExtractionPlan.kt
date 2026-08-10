package com.itsaky.androidide.lsp.kotlin.utils.refactor

/** A half-open offset range `[start, end)` into the analysed file's text. */
data class TextSpan(
	val start: Int,
	val end: Int,
) {
	init {
		require(start <= end) { "start=$start > end=$end" }
	}

	val length: Int get() = end - start

	fun overlaps(other: TextSpan): Boolean = start < other.end && other.start < end
}

/**
 * How the new declaration is woven into an anchor scope. Kotlin scopes are not all blocks, so
 * three shapes are needed; [ExistingBlock] is by far the common one.
 */
sealed interface AnchorForm {
	/**
	 * The scope already has a `{ ... }` body (function body, `if` block, lambda body, ...), so the
	 * declaration is a new statement line inside it.
	 *
	 * [statementSpans] are the block's direct child statements, ascending. The anchor point is the
	 * first of them containing the first served occurrence -- which is what makes an outer rung differ
	 * from an inner one. Anchoring on the occurrence's own line instead would make every rung of a
	 * chain produce the same edit.
	 *
	 * [contentSpan] is the region *inside* the braces. It tells a block written on one line
	 * (`items.map { it.length + 1 }`) from a multi-line one, where inserting at the statement's line
	 * start would put the declaration outside the braces.
	 */
	data class ExistingBlock(
		val contentSpan: TextSpan,
		val statementSpans: List<TextSpan>,
	) : AnchorForm

	/**
	 * A braceless statement position -- `if (c) foo()`, a `when` entry, a braceless loop body.
	 * `[bodyStart, bodyEnd)` (the statement) is replaced by a braced block holding the declaration
	 * and the original statement. No `return` is involved.
	 */
	data class WrapInBraces(
		val bodyStart: Int,
		val bodyEnd: Int,
		val indent: String,
		val innerIndent: String,
	) : AnchorForm

	/**
	 * An expression-bodied function or property accessor -- `fun area(r: Int) = r * r`. The `=` and
	 * the body are replaced by a block body. [needsReturn] is false only when the declaration
	 * returns `Unit`, where `return` is both unnecessary and wrong for a non-`Unit` expression.
	 *
	 * [returnTypeText] is the type to write into the signature, or null when there is nothing to write
	 * -- the declaration already spells its type out, or the block body infers `Unit` anyway. A block
	 * body with no declared type returns `Unit`, so `return <value>` without this would not compile.
	 */
	data class ConvertExpressionBody(
		val assignStart: Int,
		val bodyStart: Int,
		val bodyEnd: Int,
		val indent: String,
		val innerIndent: String,
		val needsReturn: Boolean,
		val returnTypeText: String? = null,
	) : AnchorForm
}

/**
 * One member of a candidate's legal scope chain: a place the declaration may go, together with the
 * occurrences that are sound to replace there.
 *
 * [occurrences] is ascending by offset and always contains the candidate's own span, so
 * `occurrences.size` is the count shown as "Replace all N occurrences". Narrowing to an inner scope
 * can only shrink this set, never grow it.
 */
data class ScopeOption(
	val label: String,
	val anchorForm: AnchorForm,
	val occurrences: List<TextSpan>,
)

/**
 * A legal extraction target and everything the UI needs to act on it.
 *
 * [label] is the expression's source text with runs of whitespace collapsed, so a multi-line
 * expression stays readable in a one-line list item.
 *
 * [scopes] is the legal scope chain, innermost first, and is never empty -- a candidate with no
 * legal anchor is not a candidate.
 */
data class CandidateExpression(
	val label: String,
	val span: TextSpan,
	val suggestedName: String,
	val takenNames: Set<String>,
	val scopes: List<ScopeOption>,
)

/**
 * The complete result of the background analysis pass, and the central type of the extract/inline
 * refactorings.
 *
 * ## Vocabulary
 *
 * Used verbatim throughout this package, its tests and its review comments -- prefer these over
 * ad-hoc synonyms.
 *
 * - **Candidate expression** -- a [org.jetbrains.kotlin.psi.KtExpression] at the cursor or selection
 *   that is a legal extraction target. At most [MAX_CANDIDATES], ordered innermost-first.
 * - **Legal scope chain** -- the ordered anchors available for the new declaration: outward from the
 *   candidate's own statement through enclosing blocks, crossing a lambda boundary only when nothing
 *   lambda-scoped is referenced, and stopping at the enclosing method body.
 * - **Anchor scope** -- the chain member the user picked. The `val` is declared inside it.
 * - **Anchor point** -- the exact insertion offset: the start of the line holding the first statement
 *   *within the anchor scope* that contains a replaced occurrence, or inside the braces when that
 *   statement shares its line with a block written on one line.
 * - **Occurrence** -- a site inside the anchor scope that is structurally equal to the candidate *and*
 *   whose every name reference resolves to the same symbol. Sites made unsound by an intervening
 *   reassignment are excluded, so an occurrence set is always safe to replace wholesale.
 * - **Extraction plan** -- this type.
 *
 * ## Why plain data
 *
 * The user's choices (which expression, what name, which scope, replace-all or not) arrive *after*
 * analysis, from a sheet. Rather than re-entering analysis on confirm, one background pass produces
 * this plan for *all* candidates at once and the UI does pure string/offset arithmetic on it. That
 * keeps PSI off the UI thread, removes the stale-PSI window, and makes the whole derivation
 * unit-testable without an editor, an activity or Compose.
 *
 * [fileText] is the text the offsets here refer to, carried so the UI can build the replacement text
 * without PSI; [documentVersion] is what makes that safe -- if the live document has moved on by the
 * time the user confirms, the plan is discarded rather than applied against shifted offsets.
 *
 * [selectionMatchedCandidate] is true when the user's selection exactly matched the innermost
 * candidate, meaning they already expressed which expression they want and the UI should not ask.
 */
data class ExtractionPlan(
	override val fileText: String,
	override val documentVersion: Int,
	val candidates: List<CandidateExpression>,
	val selectionMatchedCandidate: Boolean,
) : RefactoringPlan {
	val isEmpty: Boolean get() = candidates.isEmpty()

	companion object {
		fun empty(
			fileText: String = "",
			documentVersion: Int = -1,
		) = ExtractionPlan(fileText, documentVersion, emptyList(), selectionMatchedCandidate = false)
	}
}

/**
 * Collapses whitespace runs so a multi-line expression reads as one line in a list item.
 *
 * The space before a `.` or `?.` is then removed: a wrapped call chain is the most common multi-line
 * expression in Kotlin, and a plain collapse turns `items\n\t.filter { ... }` into
 * `items .filter { ... }`, which reads as a typo in a list the user is choosing from.
 */
internal fun collapseForLabel(
	text: String,
	maxLength: Int = 80,
): String {
	val collapsed =
		text
			.replace(WHITESPACE_RUN, " ")
			.replace(SPACE_BEFORE_DOT, "$1")
			.trim()
	return if (collapsed.length <= maxLength) collapsed else collapsed.take(maxLength - 3) + "..."
}

private val WHITESPACE_RUN = Regex("\\s+")
private val SPACE_BEFORE_DOT = Regex(" (\\??\\.)")
