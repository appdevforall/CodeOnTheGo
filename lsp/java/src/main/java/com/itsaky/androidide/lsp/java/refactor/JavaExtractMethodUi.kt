package com.itsaky.androidide.lsp.java.refactor

import com.itsaky.androidide.lsp.ui.ExtractMethodSelection
import com.itsaky.androidide.lsp.ui.MethodCandidateView

/**
 * The plan as the shared sheet sees it: labels, names and the two halves of the signature, no trees and
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
