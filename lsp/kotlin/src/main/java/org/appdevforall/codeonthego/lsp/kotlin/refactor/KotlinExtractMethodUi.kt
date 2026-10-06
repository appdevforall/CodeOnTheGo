package org.appdevforall.codeonthego.lsp.kotlin.refactor

import org.appdevforall.codeonthego.lsp.kotlin.utils.refactor.ExtractMethodCandidate
import org.appdevforall.codeonthego.lsp.kotlin.utils.refactor.ExtractMethodPlan
import org.appdevforall.codeonthego.lsp.kotlin.utils.refactor.signaturePrefix
import org.appdevforall.codeonthego.lsp.kotlin.utils.refactor.signatureSuffix
import org.appdevforall.codeonthego.lsp.ui.ExtractMethodSelection
import org.appdevforall.codeonthego.lsp.ui.MethodCandidateView

/**
 * The plan as the shared sheet sees it: labels, names and the two halves of the signature, no PSI and
 * no offsets.
 *
 * Offsets stay on this side deliberately -- the sheet is a chooser, and resolving a selection back into
 * a candidate is [candidateFor]'s job.
 */
fun ExtractMethodPlan.toMethodCandidateViews(): List<MethodCandidateView> =
	candidates.map { candidate ->
		MethodCandidateView(
			label = candidate.label,
			suggestedName = candidate.suggestedName,
			takenNames = candidate.takenNames,
			signaturePrefix = candidate.signaturePrefix,
			signatureSuffix = candidate.signatureSuffix,
		)
	}

/**
 * Resolves a selection's index back to the plan it came from, or null when it does not address it.
 *
 * A null is a wiring bug rather than a user path -- the sheet only ever reports an index it was given --
 * so the caller reports it as a failed quick fix rather than guessing at a candidate.
 */
fun ExtractMethodPlan.candidateFor(selection: ExtractMethodSelection): ExtractMethodCandidate? =
	candidates.getOrNull(selection.candidateIndex)
