package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.ui.CandidateView
import com.itsaky.androidide.lsp.ui.ExtractVariableSelection
import com.itsaky.androidide.lsp.ui.NameMessages
import com.itsaky.androidide.lsp.ui.ScopeView
import com.itsaky.androidide.resources.R

/**
 * Java's wording for the four name problems the shared sheet can report.
 *
 * Two of them name the language, which is why the sheet takes them rather than looking them up.
 */
val JAVA_NAME_MESSAGES =
	NameMessages(
		blank = R.string.msg_extract_variable_name_blank,
		invalid = R.string.msg_extract_variable_name_invalid_java,
		keyword = R.string.msg_extract_variable_name_keyword_java,
		taken = R.string.msg_extract_variable_name_taken,
	)

/**
 * The plan as the shared sheet sees it: labels, names and counts, no trees and no offsets.
 *
 * Offsets stay on this side deliberately -- the sheet is a chooser, and resolving a selection back into
 * spans is [candidateAndScopeFor]'s job.
 */
fun ExtractionPlan.toCandidateViews(): List<CandidateView> =
	candidates.map { candidate ->
		CandidateView(
			label = candidate.label,
			suggestedName = candidate.suggestedName,
			takenNames = candidate.takenNames,
			scopes =
				candidate.scopes.map { scope ->
					ScopeView(label = scope.label, occurrenceCount = scope.occurrences.size)
				},
		)
	}

/**
 * Resolves a selection's indices back to the plan they came from, or null when they do not address it.
 *
 * A null is a wiring bug rather than a user path -- the sheet only ever reports indices it was given --
 * so the caller reports it as a failed quick fix rather than guessing at a candidate.
 */
fun ExtractionPlan.candidateAndScopeFor(selection: ExtractVariableSelection): Pair<CandidateExpression, ScopeOption>? {
	val candidate = candidates.getOrNull(selection.candidateIndex) ?: return null
	val scope = candidate.scopes.getOrNull(selection.scopeIndex) ?: return null
	return candidate to scope
}
