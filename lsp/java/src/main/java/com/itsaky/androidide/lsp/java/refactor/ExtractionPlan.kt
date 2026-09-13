package com.itsaky.androidide.lsp.java.refactor

import androidx.annotation.StringRes
import com.itsaky.androidide.lsp.refactor.BlockAnchor
import com.itsaky.androidide.lsp.refactor.BracelessBody
import com.itsaky.androidide.lsp.refactor.TextSpan

/** Not every Java scope is a block: a lambda and a `->` switch rule can have an expression body. */
sealed interface AnchorForm {
	/** A scope that already has braces, described by [BlockAnchor]. */
	data class ExistingBlock(
		val block: BlockAnchor,
	) : AnchorForm

	/** A braceless position: `if (c) foo();`, a braceless loop body, a single-statement switch rule. */
	data class WrapInBraces(
		val body: BracelessBody,
	) : AnchorForm

	/**
	 * An expression-bodied lambda (`x -> x * 2`) or switch rule (`case A -> x * 2;`).
	 *
	 * [needsReturn] is false when the target's method returns `void`, where returning a value would not
	 * compile. [returnKeyword] is `yield` for a switch rule, which produces a value rather than
	 * returning from the enclosing method. Nothing is written into a signature: a Java lambda takes its
	 * type from its target, not its body.
	 */
	data class ConvertExpressionBody(
		val bodyStart: Int,
		val bodyEnd: Int,
		val indent: String,
		val innerIndent: String,
		val needsReturn: Boolean,
		val returnKeyword: String = "return",
	) : AnchorForm
}

/**
 * A rung's name for the chooser, as a resource id rather than text.
 *
 * These render in the sheet, so the copy and its word order belong in `strings.xml` where a translator
 * can reach them -- `"method $name"` fixes an English word order in code. [argument] is the one variable
 * part, filled positionally.
 */
data class ScopeLabel(
	@StringRes val res: Int,
	val argument: String? = null,
)

/**
 * A place the declaration may go, with the occurrences that are sound to replace there.
 *
 * [occurrences] always contains the candidate's own span, so its size is the count shown as "Replace
 * all N occurrences". A block rung drops leading occurrences whose anchor statement cannot host the
 * declaration: a replace-all anchors on the first served one, so keeping an unhostable site would
 * refuse the whole rewrite. Lowering N is the point -- it stays achievable.
 */
data class ScopeOption(
	val label: ScopeLabel,
	val anchorForm: AnchorForm,
	val occurrences: List<TextSpan>,
)

/**
 * A legal extraction target and everything needed to act on it.
 *
 * [declaredType] is always spelled out: Java requires a type on a local, and `var` is Java 10+ while an
 * opened project may be on `sourceCompatibility 1.8`. [scopes] is innermost first and never empty -- a
 * candidate with no legal anchor is not a candidate.
 */
data class CandidateExpression(
	val label: String,
	val span: TextSpan,
	val declaredType: String,
	val suggestedName: String,
	val takenNames: Set<String>,
	val scopes: List<ScopeOption>,
)

/**
 * The result of one background analysis pass, covering every candidate, so the confirm path does pure
 * offset arithmetic and never re-enters javac.
 *
 * [fileText] is the *compiled* unit's own content, never the editor's buffer read a moment later, since
 * every span here was computed against it. [documentVersion] is re-read on confirm: a plan computed
 * against text the user has since edited is discarded rather than applied against shifted offsets. It is
 * null when the document was not open at plan time -- nullable rather than a sentinel, because a sentinel
 * compares equal to itself and so passes the very guard it exists to fail.
 */
data class ExtractionPlan(
	val fileText: String,
	val documentVersion: Int?,
	val candidates: List<CandidateExpression>,
) {
	val isEmpty: Boolean get() = candidates.isEmpty()

	companion object {
		fun empty(
			fileText: String = "",
			documentVersion: Int? = null,
		) = ExtractionPlan(fileText, documentVersion, emptyList())
	}
}

/**
 * Collapses whitespace so a multi-line expression reads as one line in a list item.
 *
 * The space before a `.` goes too: a plain collapse turns `items\n\t.stream()` into `items .stream()`,
 * which reads as a typo in a list the user is choosing from.
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
private val SPACE_BEFORE_DOT = Regex(" (\\.)")
