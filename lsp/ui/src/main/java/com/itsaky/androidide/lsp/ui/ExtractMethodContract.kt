package com.itsaky.androidide.lsp.ui

/**
 * What the extract-method sheet needs to know about one extractable region.
 *
 * Deliberately a *view* of a language's plan rather than the plan itself: strings, name sets and index
 * positions only. That is what lets one sheet serve both language servers without either depending on
 * the other, and without this module knowing what a `KtExpression` or a `Tree` is. Each caller maps its
 * own plan into these and maps an [ExtractMethodSelection] back out.
 *
 * The signature arrives **split around the name** rather than pre-rendered, because the preview has to
 * follow what the user types. Java composes `private static int ` + name + `(int a, int b) throws
 * IOException`; Kotlin composes `private suspend fun ` + name + `(id: String): User`. Each language
 * keeps one derivation, shared by its edit builder and this preview, so the two cannot drift.
 *
 * [takenNames] is what a method declared at the insertion point would collide with, used to reject a
 * typed name.
 */
data class MethodCandidateView(
	val label: String,
	val suggestedName: String,
	val takenNames: Set<String>,
	val signaturePrefix: String,
	val signatureSuffix: String,
) {
	/** The signature exactly as the emitted declaration will read, for [name]. */
	fun signatureFor(name: String): String = signaturePrefix + name + signatureSuffix
}

/**
 * The user's finished decision.
 *
 * Positional rather than resolved: the caller knows which candidate the index names, and turning it
 * back into offsets and edits is its job. Keeping the sheet free of offsets is what makes it a pure
 * chooser.
 */
data class ExtractMethodSelection(
	val candidateIndex: Int,
	val name: String,
)
