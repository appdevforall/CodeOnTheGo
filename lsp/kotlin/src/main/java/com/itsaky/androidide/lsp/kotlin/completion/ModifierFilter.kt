package com.itsaky.androidide.lsp.kotlin.completion

import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*

/**
 * Helper for filtering modifier keywords for keyword completions.
 */
object ModifierFilter {

	/**
	 * Returns which modifier keywords are valid to suggest given the
	 * current context, declaration kind, and already-present modifiers.
	 */
	fun validModifiers(
		ctx: CursorContext,
	): Set<KtModifierKeywordToken> {
		val (_, _, _, _, _, _, declCtx, declKind, existing, _) = ctx

		val candidates = MODIFIER_KEYWORDS_ARRAY.toMutableSet()
		candidates -= existing

		// remove mutually exclusive groups
		if (VISIBILITY_MODIFIERS.types.any { it in existing })
			candidates -= VISIBILITY_MODIFIERS.types()
		if (MODALITY_MODIFIERS.types.any { it in existing })
			candidates -= MODALITY_MODIFIERS.types()

		when (declCtx) {
			DeclarationContext.INTERFACE_BODY -> {
				// interface members are open by default; sealed/final don't apply to members
				candidates -= setOf(FINAL_KEYWORD, OPEN_KEYWORD, SEALED_KEYWORD)

				// inner classes not allowed in interfaces
				candidates -= INNER_KEYWORD
			}

			DeclarationContext.FUNCTION_BODY -> {
				// local declarations: only a small subset of modifiers are legal
				candidates.retainAll(
					setOf(
						INLINE_KEYWORD, NOINLINE_KEYWORD, CROSSINLINE_KEYWORD,
						SUSPEND_KEYWORD, TAILREC_KEYWORD,
						DATA_KEYWORD,         // local data class (Kotlin 1.9+)
						INNER_KEYWORD,
					)
				)
			}

			DeclarationContext.OBJECT_BODY,
			DeclarationContext.TOP_LEVEL,
			DeclarationContext.SCRIPT_TOP_LEVEL -> candidates -= INNER_KEYWORD     // inner only valid inside a class

			else -> Unit
		}

		when (declKind) {
			DeclarationKind.PROPERTY_VAL -> {
				candidates -= setOf(
					LATEINIT_KEYWORD,  // lateinit requires var
					VARARG_KEYWORD,
					NOINLINE_KEYWORD, CROSSINLINE_KEYWORD,
					TAILREC_KEYWORD, OPERATOR_KEYWORD, INFIX_KEYWORD,
					INNER_KEYWORD, COMPANION_KEYWORD, DATA_KEYWORD,
					ENUM_KEYWORD, ANNOTATION_KEYWORD, SEALED_KEYWORD,
					VALUE_KEYWORD,
				)

				// const only on top-level or companion object val
				if (declCtx !in setOf(
						DeclarationContext.TOP_LEVEL,
						DeclarationContext.OBJECT_BODY,
						DeclarationContext.SCRIPT_TOP_LEVEL
					)
				)
					candidates -= CONST_KEYWORD
			}

			DeclarationKind.PROPERTY_VAR -> {
				candidates -= setOf(
					CONST_KEYWORD,     // const requires val
					VARARG_KEYWORD, NOINLINE_KEYWORD, CROSSINLINE_KEYWORD,
					TAILREC_KEYWORD, OPERATOR_KEYWORD, INFIX_KEYWORD,
					INNER_KEYWORD, COMPANION_KEYWORD, DATA_KEYWORD,
					ENUM_KEYWORD, ANNOTATION_KEYWORD, SEALED_KEYWORD,
					VALUE_KEYWORD,
				)
			}

			DeclarationKind.FUN -> {
				candidates -= setOf(
					LATEINIT_KEYWORD, CONST_KEYWORD, VARARG_KEYWORD,
					INNER_KEYWORD, COMPANION_KEYWORD, DATA_KEYWORD,
					ENUM_KEYWORD, ANNOTATION_KEYWORD, SEALED_KEYWORD,
					VALUE_KEYWORD,
				)
				// abstract fun can't be inline/tailrec/external simultaneously
				if (ABSTRACT_KEYWORD in existing) {
					candidates -= setOf(INLINE_KEYWORD, TAILREC_KEYWORD, EXTERNAL_KEYWORD)
				}
			}

			DeclarationKind.CLASS -> {
				candidates -= setOf(
					LATEINIT_KEYWORD, CONST_KEYWORD,
					VARARG_KEYWORD, NOINLINE_KEYWORD, CROSSINLINE_KEYWORD,
					TAILREC_KEYWORD, OPERATOR_KEYWORD, INFIX_KEYWORD,
					REIFIED_KEYWORD,
				)

				// sealed is a modality modifier and conflicts with open/final/abstract
				if (SEALED_KEYWORD in existing)
					candidates -= setOf(OPEN_KEYWORD, FINAL_KEYWORD, ABSTRACT_KEYWORD)

				// value class requires @JvmInline in practice, but `value` keyword is valid
			}

			DeclarationKind.INTERFACE -> {
				// interfaces are implicitly abstract; most modifiers don't apply
				candidates.retainAll(
					setOf(
						PUBLIC_KEYWORD, PROTECTED_KEYWORD, PRIVATE_KEYWORD, INTERNAL_KEYWORD,
						EXPECT_KEYWORD, ACTUAL_KEYWORD,
						SEALED_KEYWORD,       // sealed interface
						EXTERNAL_KEYWORD, FUN_KEYWORD,  // fun interface
					)
				)
			}

			DeclarationKind.OBJECT -> {
				candidates.retainAll(
					setOf(
						PUBLIC_KEYWORD, PROTECTED_KEYWORD, PRIVATE_KEYWORD, INTERNAL_KEYWORD,
						EXPECT_KEYWORD, ACTUAL_KEYWORD, EXTERNAL_KEYWORD,
						DATA_KEYWORD,          // data object (Kotlin 1.9+)
						COMPANION_KEYWORD,
					)
				)
			}

			DeclarationKind.CONSTRUCTOR -> {
				// constructors only take visibility modifiers
				candidates.retainAll(VISIBILITY_MODIFIERS.types())
			}

			DeclarationKind.UNKNOWN -> {
				// Cursor is after some modifiers but before any keyword.
				// Keep all modifiers that are valid given what's already typed;
				// the exclusion rules above already handled mutual exclusions.
			}

			else -> Unit
		}

		// expect and actual are mutually exclusive
		if (EXPECT_KEYWORD in existing) candidates -= ACTUAL_KEYWORD
		if (ACTUAL_KEYWORD in existing) candidates -= EXPECT_KEYWORD

		// noinline, crossinline and reified keywords are invalid if the
		// function is not inline
		if (INLINE_KEYWORD !in existing) {
			candidates -= setOf(NOINLINE_KEYWORD, CROSSINLINE_KEYWORD, REIFIED_KEYWORD)
		}

		return candidates
	}

	private fun TokenSet.types(): Set<KtModifierKeywordToken> =
		types.filterIsInstance<KtModifierKeywordToken>().toSet()

	private operator fun TokenSet.contains(token: KtModifierKeywordToken): Boolean =
		this.contains(token)
}