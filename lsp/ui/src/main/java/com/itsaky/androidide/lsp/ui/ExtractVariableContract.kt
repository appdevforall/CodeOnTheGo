package com.itsaky.androidide.lsp.ui

/**
 * What the extract-variable sheet needs to know about one candidate expression.
 *
 * Deliberately a *view* of a language's plan rather than the plan itself: strings, counts and index
 * positions only. That is what lets one sheet serve both language servers without either of them
 * depending on the other, and without this module knowing what a `KtExpression` or an `ExpressionTree`
 * is. Each caller maps its own plan into these and maps an [ExtractVariableSelection] back out.
 *
 * [takenNames] is what a new declaration at this candidate would collide with or shadow, used to
 * reject a typed name.
 *
 * [scopes] is the legal scope chain, innermost first, and is never empty -- a candidate with no legal
 * anchor is not offered.
 */
data class CandidateView(
	val label: String,
	val suggestedName: String,
	val takenNames: Set<String>,
	val scopes: List<ScopeView>,
) {
	init {
		require(scopes.isNotEmpty()) { "candidate '$label' has no scopes" }
	}
}

/**
 * One place the declaration may go.
 *
 * [occurrenceCount] counts every site this scope would rewrite, **including** the one the user
 * selected, so "Replace all 3 occurrences" means three sites in total.
 */
data class ScopeView(
	val label: String,
	val occurrenceCount: Int,
)

/**
 * The user's finished decision.
 *
 * Positional rather than resolved: the caller knows which candidate and scope these indices name, and
 * turning them back into offsets and an edit is its job. Keeping the sheet free of offsets is what
 * makes it a pure chooser.
 */
data class ExtractVariableSelection(
	val candidateIndex: Int,
	val scopeIndex: Int,
	val name: String,
	val replaceAll: Boolean,
)

/**
 * The four name-problem strings, supplied per language.
 *
 * Two of them name the language ("Not a valid Java name"), so a shared res-id lookup would put
 * Kotlin's wording in front of a Java user. Passing the ids keeps this module language-agnostic
 * without genericising the copy into something less useful.
 */
data class NameMessages(
	val blank: Int,
	val invalid: Int,
	val keyword: Int,
	val taken: Int,
) {
	fun resFor(problem: NameProblem): Int =
		when (problem) {
			NameProblem.Blank -> blank
			NameProblem.NotAnIdentifier -> invalid
			NameProblem.Keyword -> keyword
			NameProblem.AlreadyTaken -> taken
		}
}
