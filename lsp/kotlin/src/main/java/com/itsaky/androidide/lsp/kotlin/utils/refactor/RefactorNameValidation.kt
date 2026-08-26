package com.itsaky.androidide.lsp.kotlin.utils.refactor

// The name-validation half of the extract refactorings lives in this module, not the carrier,
// because the refactoring UI needs it and the UI lives here (ADR 0013) -- while the carrier's own
// PSI-driven name *suggestion* needs it too. Pure string and set work with no Analysis API in it,
// so leaving it out of the carrier costs nothing. Public rather than private/internal only because
// the carrier reads it across a module boundary, where neither reaches (ADFA-5010 stage merge).

/**
 * Kotlin's hard keywords -- the ones that are never valid identifiers. Soft and modifier keywords
 * (`by`, `data`, `it`, ...) are legal names and are deliberately absent.
 */
val HARD_KEYWORDS =
	setOf(
		"as",
		"break",
		"class",
		"continue",
		"do",
		"else",
		"false",
		"for",
		"fun",
		"if",
		"in",
		"interface",
		"is",
		"null",
		"object",
		"package",
		"return",
		"super",
		"this",
		"throw",
		"true",
		"try",
		"typealias",
		"typeof",
		"val",
		"var",
		"when",
		"while",
	)

/** Why a proposed name cannot be used. Null-free alternative to throwing for user input. */
enum class NameProblem {
	Blank,
	NotAnIdentifier,
	Keyword,
	AlreadyTaken,
}

/**
 * Validates a user-supplied name against Kotlin's identifier rules and the names already visible at
 * the anchor point. Returns null when the name is usable.
 *
 * Backtick-quoted names are rejected rather than supported: they are legal Kotlin but a poor
 * suggestion for a generated local, and accepting them would mean validating the quoted form too.
 */
fun validateVariableName(
	name: String,
	takenNames: Set<String>,
): NameProblem? {
	if (name.isBlank()) return NameProblem.Blank
	if (!isIdentifier(name)) return NameProblem.NotAnIdentifier
	if (name in HARD_KEYWORDS) return NameProblem.Keyword
	if (name in takenNames) return NameProblem.AlreadyTaken
	return null
}

fun isIdentifier(name: String): Boolean {
	if (name.isEmpty()) return false
	if (!(name[0].isLetter() || name[0] == '_')) return false
	return name.all { it.isLetterOrDigit() || it == '_' }
}
