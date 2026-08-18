package com.itsaky.androidide.lsp.kotlin.utils.refactor

/** One derived parameter of the new function. Names are the originals, unchanged (R5). */
data class MethodParameter(
	val name: String,
	val typeText: String,
)

/** What goes inside the new function's braces. */
sealed interface ExtractedBody {
	/**
	 * The region's expression text. [needsReturn] is false only for a `Unit`-valued expression, where
	 * the function returns `Unit` and a bare statement reads better than `return println(x)`.
	 */
	data class ExpressionBody(
		val needsReturn: Boolean,
	) : ExtractedBody

	/**
	 * The statements verbatim. [trailingReturn] is the `return <output>` line appended for the
	 * single-output case, and null otherwise -- including the tail-return case, where the region
	 * already ends in a `return`.
	 */
	data class StatementBody(
		val trailingReturn: String?,
	) : ExtractedBody
}

/** How the region's own text is replaced (R6). */
sealed interface CallSiteForm {
	/** `extracted(args)` -- an expression in place, or a statement. */
	data object Call : CallSiteForm

	/** `val x = extracted(args)` for the single output [name]. */
	data class AssignOutput(
		val name: String,
	) : CallSiteForm

	/** `return extracted(args)` for the tail-return case (R8). */
	data object Return : CallSiteForm
}

/**
 * One extractable region, fully derived: everything the sheet renders and the edit builder emits,
 * with no PSI left in it.
 *
 * [span] is what the call site replaces. [insertOffset] is the end of the enclosing declaration --
 * the new function goes immediately after it (R4) -- and [insertIndent] is that declaration's own
 * indentation, since nothing re-indents a code-action edit after it is applied.
 *
 * [returnTypeText] is null for a `Unit` function, where the `: Unit` is left off.
 *
 * [rawStringSpans] are the raw (triple-quoted) string literals inside the region, in file offsets.
 * Their interior is whitespace-sensitive, so re-indentation must leave those lines byte-for-byte
 * (ADR 0013).
 */
data class ExtractMethodCandidate(
	val label: String,
	val span: TextSpan,
	val suggestedName: String,
	val takenNames: Set<String>,
	val annotations: List<String>,
	val modifiers: List<String>,
	val receiverTypeText: String?,
	val parameters: List<MethodParameter>,
	val returnTypeText: String?,
	val body: ExtractedBody,
	val callSite: CallSiteForm,
	val insertOffset: Int,
	val insertIndent: String,
	val rawStringSpans: List<TextSpan> = emptyList(),
)

/**
 * Why a region could not be extracted. A refusal is a designed outcome, not an error (ADR 0013):
 * each reason gets its own message naming the construct in the way, because a generic one reads as
 * the feature being broken.
 */
sealed interface ExtractionRefusal {
	/** The selection is neither one expression nor whole statements inside one block (R2). */
	data object NotASingleRegion : ExtractionRefusal

	/**
	 * The analysis could not run at all -- no compilation environment, no `KtFile`, or something threw.
	 * Deliberately neutral: the selection may have been perfectly good, so it must not be blamed the way
	 * [NotASingleRegion] blames it.
	 */
	data object CouldNotAnalyse : ExtractionRefusal

	/**
	 * The region declares two or more values the code after it still needs, and one return cannot carry
	 * them (R7). [names] is what is in the way, so the message can name them.
	 */
	data class MultipleOutputs(
		val names: List<String>,
	) : ExtractionRefusal

	/**
	 * The region declares exactly one thing the code after it still needs, but the call site cannot
	 * receive it back (R7): a destructuring entry or a local `fun`, which a `val` cannot stand in for,
	 * or a local the following code reassigns, which a `val` cannot be.
	 */
	data class OutputNotReturnable(
		val name: String,
	) : ExtractionRefusal

	/** A `var` declared outside the region is assigned inside it. ADFA-5082 lifts this (R7). */
	data class ReassignsOuterVar(
		val name: String,
	) : ExtractionRefusal

	/** A `return`, `break` or `continue` whose target is outside the region (R8). */
	data object ExitsRegion : ExtractionRefusal

	/**
	 * The region sits inside an anonymous extension function (R4). The new function is a sibling of the
	 * enclosing *named* declaration, so it would be generated on that declaration's receiver -- or on no
	 * receiver at all -- rather than on the one the region's body actually reads.
	 */
	data object AnonymousExtensionFunction : ExtractionRefusal

	/** Members of a `with`/`apply`/`run` receiver introduced inside the enclosing declaration (R9). */
	data class InnerImplicitReceiver(
		val construct: String,
	) : ExtractionRefusal

	/** A type parameter declared on the enclosing function (R10). */
	data class UsesTypeParameter(
		val name: String,
	) : ExtractionRefusal

	/** A parameter or return type that cannot be written out as source (R5). */
	data object UnrenderableType : ExtractionRefusal

	/**
	 * A property accessor's `field` (R4). The backing field is reachable only from inside the
	 * accessor, so the reference would move verbatim into the new function and stop resolving.
	 */
	data object UsesBackingField : ExtractionRefusal

	/**
	 * A captured value the region uses through a smart cast (R5). Its declared type does not compile
	 * in the new body and its narrowed type does not compile at the call site, so neither emission is
	 * faithful (ADR 0013).
	 */
	data class SmartCastParameter(
		val name: String,
	) : ExtractionRefusal

	/**
	 * A local `fun`, class or object the region uses but does not contain (R5). It goes out of scope
	 * once the region moves, and only values can be handed over as parameters.
	 */
	data class CapturedLocalDeclaration(
		val name: String,
	) : ExtractionRefusal
}

/**
 * The complete result of the background pass.
 *
 * Unlike extract variable's plan this carries a [refusal] rather than merely being empty, because
 * "why not" is most of what this refactoring has to say (ADR 0013). [candidates] and [refusal] are
 * mutually exclusive in practice: a non-empty candidate list means at least one region survived.
 */
data class ExtractMethodPlan(
	override val fileText: String,
	override val documentVersion: Int,
	val candidates: List<ExtractMethodCandidate>,
	val selectionMatchedCandidate: Boolean,
	val refusal: ExtractionRefusal?,
) : RefactoringPlan {
	val isEmpty: Boolean get() = candidates.isEmpty()

	companion object {
		fun refused(
			refusal: ExtractionRefusal,
			fileText: String = "",
			documentVersion: Int = -1,
		) = ExtractMethodPlan(fileText, documentVersion, emptyList(), selectionMatchedCandidate = false, refusal = refusal)
	}
}

/**
 * The signature exactly as [buildExtractMethodRewrites] emits it. The sheet's preview calls this, so
 * there is one derivation and the preview cannot drift from the declaration (R11).
 */
fun ExtractMethodCandidate.signatureText(name: String): String =
	buildString {
		annotations.forEach { append(it).append(' ') }
		modifiers.forEach { append(it).append(' ') }
		append("fun ")
		receiverTypeText?.let { append(it).append('.') }
		append(name)
		append('(')
		append(parameters.joinToString(", ") { "${it.name}: ${it.typeText}" })
		append(')')
		returnTypeText?.let { append(": ").append(it) }
	}
