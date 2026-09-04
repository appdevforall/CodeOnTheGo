package com.itsaky.androidide.lsp.ui

/** Why a proposed name cannot be used. Null-free alternative to throwing for user input. */
enum class NameProblem {
	Blank,
	NotAnIdentifier,
	Keyword,
	AlreadyTaken,
}

/**
 * Validates a user-supplied name against the language's identifier rules and the names already
 * visible at the anchor point. Returns null when the name is usable.
 *
 * Lives here rather than in a language module because the sheet's Extract button is gated on it and
 * its result selects the message shown under the text field. [keywords] is the set the language
 * rejects outright: both callers pass only the words that are never legal identifiers -- Kotlin's hard
 * keywords, Java's reserved words -- since a language's soft or restricted keywords are legal names.
 */
fun validateVariableName(
	name: String,
	takenNames: Set<String>,
	keywords: Set<String>,
): NameProblem? {
	if (name.isBlank()) return NameProblem.Blank
	if (!isIdentifier(name)) return NameProblem.NotAnIdentifier
	if (name in keywords) return NameProblem.Keyword
	if (name in takenNames) return NameProblem.AlreadyTaken
	return null
}

/**
 * The identifier shape both languages share: a letter or underscore, then letters, digits and
 * underscores.
 *
 * Deliberately the intersection rather than either language's full grammar. A generated local has no
 * reason to need more, and it is what rejects Kotlin's backtick-quoted names -- legal Kotlin, but a
 * poor generated name, and accepting them would mean validating the quoted form too.
 */
fun isIdentifier(name: String): Boolean {
	if (name.isEmpty()) return false
	if (!(name[0].isLetter() || name[0] == '_')) return false
	return name.all { it.isLetterOrDigit() || it == '_' }
}
