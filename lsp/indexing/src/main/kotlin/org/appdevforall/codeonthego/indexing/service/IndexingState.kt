package org.appdevforall.codeonthego.indexing.service

/**
 * Whether source indexing is in flight, as [IndexingServiceManager.state] reports it.
 *
 * Every source (a JAR) is counted once while it is pending, across all services reporting to the
 * same [IndexingProgressTracker].
 */
sealed interface IndexingState {
	/** Nothing is being indexed. */
	data object Idle : IndexingState

	/**
	 * [done] of the [total] sources submitted since the state last left [Idle] have finished,
	 * whether they were indexed, failed or were cancelled.
	 */
	data class Indexing(
		val done: Int,
		val total: Int,
	) : IndexingState
}
