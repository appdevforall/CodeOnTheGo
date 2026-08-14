package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * Where the cursor was when the action ran. Recorded because mode availability depends on it: only a
 * cursor already sitting on a reference can single that reference out.
 */
enum class InlineCursorPosition {
	Declaration,
	Reference,
}

/**
 * Why one reference cannot be rewritten. Every one of these excludes *that reference* and leaves it
 * untouched; none refuses the whole inline, because each is a property of one site.
 */
enum class InlineExclusion {
	/** At or after the cutoff: the value the declaration produced no longer holds. */
	PastCutoff,

	/** A name the initializer reads means something else here. */
	Shadowed,

	/** The initializer reads through an implicit receiver a lambda in between replaces. */
	ReceiverShift,

	/** The reference is used under a smart cast, which an expression cannot carry. */
	SmartCast,

	/** A lambda initializer in call position, which would need `.invoke()` to compile. */
	InvokesLambdaInitializer,
}

/**
 * One read of the target declaration inside the enclosing declaration.
 *
 * [span] is what the substitution replaces. For a short-form string-template entry (`$x`) it covers
 * the whole entry including the `$`, so the substitution can emit either `$name` or `${...}`.
 */
data class InlineReference(
	val span: TextSpan,
	val isShortTemplateEntry: Boolean,
	val exclusion: InlineExclusion?,
) {
	val isInlinable: Boolean get() = exclusion == null
}

/** The two things the user can ask for. */
enum class InlineMode {
	ThisReferenceOnly,
	AllReferences,
}

/**
 * Why nothing can be inlined. A refusal is a designed outcome, not an error: each reason names what
 * is in the way, because a generic message reads as the feature being broken.
 */
sealed interface InlineRefusal {
	/** The cursor is not on a variable or one of its uses at all. */
	data object NotAVariable : InlineRefusal

	/** A member or top-level property, whose references can leave the file. */
	data object NotALocalVariable : InlineRefusal

	/** A local declared without a value -- the `val x: Int` then `x = 1` shape, which Kotlin permits. */
	data class NoInitializer(
		val name: String,
	) : InlineRefusal

	/** A destructuring declaration or one of its entries. */
	data object DestructuringDeclaration : InlineRefusal

	/**
	 * An explicit type on the declaration. [typeText] is the annotation as written, so the message
	 * can name it.
	 */
	data class DeclaredTypeIsLoadBearing(
		val name: String,
		val typeText: String,
	) : InlineRefusal

	/** No reference at all: inlining would be a delete-unused-variable action in disguise. */
	data class NeverUsed(
		val name: String,
	) : InlineRefusal

	/** Every reference is excluded or past the cutoff, so there is no edit to make. */
	data class NothingInlinable(
		val name: String,
	) : InlineRefusal

	/**
	 * The cursor is on a reference that cannot be rewritten. Rewriting the *other* references
	 * instead reads as the action having done nothing.
	 */
	data class ReferenceNotInlinable(
		val name: String,
	) : InlineRefusal

	/**
	 * The analysis could not run -- no compilation environment, no `KtFile`, or something threw.
	 * Deliberately neutral: the cursor may have been perfectly fine, so it must not be blamed.
	 */
	data object CouldNotAnalyse : InlineRefusal

	/** The file changed between building the plan and applying it. Raised by the action. */
	data object FileChanged : InlineRefusal
}

/**
 * The complete result of the background pass: plain data, no PSI, so the UI can hold it.
 *
 * [initializerNeedsParentheses] is decided from the initializer alone during analysis and consumed by
 * the edit builder, which stays pure. [cursorReferenceIndex] is -1 when the cursor was on the
 * declaration. [canDeleteDeclaration] is the conjunction of two conditions -- every reference
 * inlinable *and* the target never written -- and is honoured only by [InlineMode.AllReferences].
 */
data class InlineVariablePlan(
	override val fileText: String,
	override val documentVersion: Int,
	val variableName: String,
	val declarationSpan: TextSpan,
	val initializerText: String,
	val initializerNeedsParentheses: Boolean,
	val references: List<InlineReference>,
	val cursorPosition: InlineCursorPosition,
	val cursorReferenceIndex: Int,
	val canDeleteDeclaration: Boolean,
	val modes: List<InlineMode>,
	val refusal: InlineRefusal?,
) : RefactoringPlan {
	val inlinableReferences: List<InlineReference> get() = references.filter { it.isInlinable }

	val isRefused: Boolean get() = refusal != null

	/** Whether the mode table leaves the user a decision, and so whether the sheet is shown at all. */
	val offersChoice: Boolean get() = modes.size > 1

	companion object {
		fun refused(
			refusal: InlineRefusal,
			fileText: String = "",
			documentVersion: Int = -1,
		) = InlineVariablePlan(
			fileText = fileText,
			documentVersion = documentVersion,
			variableName = "",
			declarationSpan = TextSpan(0, 0),
			initializerText = "",
			initializerNeedsParentheses = false,
			references = emptyList(),
			cursorPosition = InlineCursorPosition.Declaration,
			cursorReferenceIndex = -1,
			canDeleteDeclaration = false,
			modes = emptyList(),
			refusal = refusal,
		)
	}
}

/**
 * The mode table. The single-inlinable-reference row collapses deliberately: "this reference only" there
 * produces the same substitution as "all references" plus a declaration nothing reads.
 *
 * A cursor on a reference that is not itself inlinable never reaches here -- the planner refuses with
 * [InlineRefusal.ReferenceNotInlinable].
 */
fun modesFor(
	cursorPosition: InlineCursorPosition,
	inlinableCount: Int,
): List<InlineMode> =
	if (cursorPosition == InlineCursorPosition.Reference && inlinableCount >= 2) {
		listOf(InlineMode.ThisReferenceOnly, InlineMode.AllReferences)
	} else {
		listOf(InlineMode.AllReferences)
	}

/**
 * What one of the mode buttons says, as data rather than as a string: the plan layer stays free of
 * Android resources and the derivation stays unit-testable, while the sheet maps each case to a
 * localised string.
 */
sealed interface InlineLabel {
	data object ThisReferenceOnly : InlineLabel

	data class AllAndDelete(
		val count: Int,
		val name: String,
	) : InlineLabel

	data class AllKeepingDeclaration(
		val count: Int,
		val name: String,
	) : InlineLabel

	data class PartialKeepingDeclaration(
		val count: Int,
		val total: Int,
		val name: String,
	) : InlineLabel
}

/** What the flash says afterwards. Same reasoning as [InlineLabel], in the past tense. */
sealed interface InlineReport {
	data class InlinedAndRemoved(
		val count: Int,
		val name: String,
	) : InlineReport

	data class InlinedKeepingDeclaration(
		val count: Int,
		val name: String,
	) : InlineReport

	data class InlinedPartially(
		val count: Int,
		val total: Int,
		val name: String,
	) : InlineReport
}

/**
 * The label for [mode], derived here rather than composed in the composable.
 *
 * "Inline all 5 references and remove `total`" versus "Inline 3 of 5 references" is exactly the string
 * that can drift from what the edit does, and the deletion rule makes the difference invisible to a
 * reader of the composable.
 */
fun InlineVariablePlan.labelFor(mode: InlineMode): InlineLabel =
	when (mode) {
		InlineMode.ThisReferenceOnly -> InlineLabel.ThisReferenceOnly

		InlineMode.AllReferences -> {
			val count = inlinableReferences.size
			when {
				count < references.size -> InlineLabel.PartialKeepingDeclaration(count, references.size, variableName)
				canDeleteDeclaration -> InlineLabel.AllAndDelete(count, variableName)
				else -> InlineLabel.AllKeepingDeclaration(count, variableName)
			}
		}
	}

/**
 * What to report once [mode] has been applied. "This reference only" always keeps the declaration and
 * always leaves other references behind, so it is a partial result by definition.
 */
fun InlineVariablePlan.reportFor(mode: InlineMode): InlineReport =
	when (mode) {
		InlineMode.ThisReferenceOnly -> InlineReport.InlinedPartially(1, references.size, variableName)

		InlineMode.AllReferences -> {
			val count = inlinableReferences.size
			when {
				count < references.size -> InlineReport.InlinedPartially(count, references.size, variableName)
				canDeleteDeclaration -> InlineReport.InlinedAndRemoved(count, variableName)
				else -> InlineReport.InlinedKeepingDeclaration(count, variableName)
			}
		}
	}
