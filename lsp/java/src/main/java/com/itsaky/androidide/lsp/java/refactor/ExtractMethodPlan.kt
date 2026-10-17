package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.refactor.TextSpan

/** One derived parameter of the new method. Names are the originals, unchanged (R5). */
data class MethodParameter(
	val name: String,
	val typeText: String,
)

/** What goes inside the new method's braces. */
sealed interface ExtractedBody {
	/**
	 * The region's expression text. [needsReturn] is false only for a `void`-typed expression, where
	 * the method returns `void` and a bare statement is all that would compile.
	 */
	data class ExpressionBody(
		val needsReturn: Boolean,
	) : ExtractedBody

	/**
	 * The statements verbatim. [trailingReturn] is the `return <output>;` line appended for the
	 * single-output case, and null otherwise -- including the tail-return case, where the region already
	 * ends in a `return`.
	 */
	data class StatementBody(
		val trailingReturn: String?,
	) : ExtractedBody
}

/** How the region's own text is replaced (R6). */
sealed interface CallSiteForm {
	/** `extracted(args)` in an expression's place. */
	data object Call : CallSiteForm

	/** `extracted(args);` as a statement, for a statement range with no output. */
	data object CallStatement : CallSiteForm

	/** `int x = extracted(args);` for the single output [name]. */
	data class AssignOutput(
		val typeText: String,
		val name: String,
	) : CallSiteForm

	/** `return extracted(args);` for the tail-return case (R8). */
	data object Return : CallSiteForm
}

/**
 * One extractable region, fully derived: everything the sheet renders and the edit builder emits, with
 * no trees left in it.
 *
 * [span] is what the call site replaces. [insertOffset] is the end of the anchor member -- the new
 * method goes immediately after it (R4) -- and [insertIndent] is that member's own indentation, since
 * nothing re-indents a code-action edit after it is applied.
 *
 * [returnTypeText] is spelled out rather than nullable: Java has no inferred method return type, so
 * `void` is a type name like any other.
 *
 * [textBlockSpans] are the text block (`"""`) literals inside the region, in file offsets. Their
 * interior whitespace is part of the literal's value, so re-indentation must leave those lines
 * byte-for-byte (ADR 0014).
 */
data class ExtractMethodCandidate(
	val label: String,
	val span: TextSpan,
	val suggestedName: String,
	val takenNames: Set<String>,
	val modifiers: List<String>,
	val parameters: List<MethodParameter>,
	val returnTypeText: String,
	val thrownTypes: List<String>,
	val body: ExtractedBody,
	val callSite: CallSiteForm,
	val insertOffset: Int,
	val insertIndent: String,
	val textBlockSpans: List<TextSpan>,
)

/**
 * Why a region could not be extracted. A refusal is a designed outcome, not an error (ADR 0014): each
 * reason gets its own message naming the construct in the way, because a generic one reads as the
 * feature being broken.
 *
 * Five of Kotlin's thirteen reasons have no Java counterpart and are deliberately absent:
 * `OutputNotReturnable` (every Java local can be received back as `T x = extracted()`),
 * `AnonymousExtensionFunction`, `InnerImplicitReceiver`, `UsesBackingField` and `SmartCastParameter`
 * (no Java construct produces any of them).
 */
sealed interface ExtractionRefusal {
	/** The selection is neither one expression nor whole statements inside one block (R2). */
	data object NotASingleRegion : ExtractionRefusal

	/**
	 * The analysis could not run at all -- no compiler, no attributed unit, or something threw.
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
	 * A variable declared outside the region is assigned inside it (R7). Java has no out parameters, so
	 * the assignment would be lost.
	 */
	data class ReassignsOuterVar(
		val name: String,
	) : ExtractionRefusal

	/** A `return`, `break`, `continue` or `yield` whose target is outside the region (R8). */
	data object ExitsRegion : ExtractionRefusal

	/** A type parameter declared on the anchor method (R10). */
	data class UsesTypeParameter(
		val name: String,
	) : ExtractionRefusal

	/** A parameter, return or thrown type that cannot be written out as source (R5). */
	data object UnrenderableType : ExtractionRefusal

	/**
	 * A local class the region uses but does not contain, or a value whose type is one (R5). The value
	 * survives the move; the type name does not.
	 */
	data class CapturedLocalDeclaration(
		val name: String,
	) : ExtractionRefusal
}

/**
 * The complete result of the background pass.
 *
 * Unlike extract variable's plan this carries a [refusal] rather than merely being empty, because "why
 * not" is most of what this refactoring has to say (ADR 0014). [candidates] and [refusal] are mutually
 * exclusive in practice: a non-empty candidate list means at least one region survived.
 */
data class ExtractMethodPlan(
	val fileText: String,
	val documentVersion: Int?,
	val candidates: List<ExtractMethodCandidate>,
	val refusal: ExtractionRefusal?,
) {
	val isEmpty: Boolean get() = candidates.isEmpty()

	companion object {
		fun refused(
			refusal: ExtractionRefusal,
			fileText: String = "",
			documentVersion: Int? = null,
		) = ExtractMethodPlan(fileText, documentVersion, emptyList(), refusal = refusal)
	}
}

/**
 * Everything the signature says before the method's name: modifiers, then the return type.
 *
 * Split from [signatureSuffix] rather than rendered whole because the sheet's preview follows what the
 * user types. Both halves compose through [signatureText], which the edit builder calls, so the preview
 * cannot drift from the emitted declaration (R11).
 */
val ExtractMethodCandidate.signaturePrefix: String
	get() =
		buildString {
			modifiers.forEach { append(it).append(' ') }
			append(returnTypeText).append(' ')
		}

/** Everything the signature says after the method's name: parameters, then any `throws` clause. */
val ExtractMethodCandidate.signatureSuffix: String
	get() =
		buildString {
			append('(')
			append(parameters.joinToString(", ") { "${it.typeText} ${it.name}" })
			append(')')
			if (thrownTypes.isNotEmpty()) append(" throws ").append(thrownTypes.joinToString(", "))
		}

/** The signature exactly as [buildExtractMethodRewrites] emits it. */
fun ExtractMethodCandidate.signatureText(name: String): String = signaturePrefix + name + signatureSuffix
