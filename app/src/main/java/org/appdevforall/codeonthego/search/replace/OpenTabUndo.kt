package org.appdevforall.codeonthego.search.replace

object OpenTabUndo {
	enum class Decision { RESTORE, CHANGED, CLOSED }

	fun decide(
		currentText: String?,
		postReplaceText: String,
	): Decision =
		when (currentText) {
			null -> Decision.CLOSED
			postReplaceText -> Decision.RESTORE
			else -> Decision.CHANGED
		}
}
