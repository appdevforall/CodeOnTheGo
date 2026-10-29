package org.appdevforall.codeonthego.indexing

/**
 * Marks whether the project session that owns a set of indexes has ended.
 *
 * Indexes of an ended session are expected to be used after close while the session's remaining
 * work winds down, so they log that at DEBUG; a live session's use after close is a bug and logs ERROR.
 */
class IndexSessionLifetime {
	@Volatile
	private var ended = false

	/** Whether [end] has been called. */
	val isEnded: Boolean
		get() = ended

	/** Ends the session; idempotent and never blocks. */
	fun end() {
		ended = true
	}
}
