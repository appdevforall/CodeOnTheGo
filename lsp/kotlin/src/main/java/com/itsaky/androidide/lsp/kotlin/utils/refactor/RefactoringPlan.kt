package com.itsaky.androidide.lsp.kotlin.utils.refactor

/**
 * What every interactive refactoring's background pass returns.
 *
 * The two fields are what makes applying a plan safe long after it was computed: [fileText] is the
 * text its offsets refer to, and [documentVersion] is re-read on confirm so a plan computed against
 * text the user has since edited is discarded rather than applied against shifted offsets.
 *
 * [documentVersion] is null when the document was not open at plan time -- nullable rather than a
 * sentinel, because a sentinel compares equal to itself and so passes the very guard it exists to fail.
 */
sealed interface RefactoringPlan {
	val fileText: String
	val documentVersion: Int?
}
