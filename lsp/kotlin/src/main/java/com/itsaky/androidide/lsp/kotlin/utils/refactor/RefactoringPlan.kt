package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * What every interactive refactoring's background pass returns.
 *
 * The two fields are what makes applying a plan safe long after it was computed: [fileText] is the
 * text its offsets refer to, and [documentVersion] is re-read on confirm so a plan computed against
 * text the user has since edited is discarded rather than applied against shifted offsets.
 */
sealed interface RefactoringPlan {
	val fileText: String
	val documentVersion: Int
}
