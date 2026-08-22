package com.itsaky.androidide.deeplink

import com.itsaky.androidide.models.DeepLinkRequest

/**
 * The deep-link requests this task has already acted on, so a redelivered Intent carrying one of
 * them does not force its project open a second time (ADFA-5067).
 *
 * Every consumed request is remembered, not just the latest. One slot was not enough: after link A
 * is consumed and link B arrives through `onNewIntent`, `setIntent` makes B the live Intent while
 * the task still holds A as its launch Intent -- and that is the Intent a recreate after process
 * death is given. A then failed a "same as the last consumed request" test and reopened its project,
 * which is the very loss the single field existed to prevent.
 *
 * Kept out of the activity so the bookkeeping can be tested without one: this is the third distinct
 * lifecycle path (config change, process death, second link) whose correctness rests entirely on it.
 */
internal class ConsumedDeepLinkRequests {
	private val requests = LinkedHashSet<DeepLinkRequest>()

	/** For `onSaveInstanceState`; pairs with [restore]. */
	fun toSavedList(): ArrayList<DeepLinkRequest> = ArrayList(requests)

	/** Replaces the contents with [saved], which is null when there is no instance state to restore. */
	fun restore(saved: List<DeepLinkRequest>?) {
		requests.clear()
		saved?.let(requests::addAll)
	}

	operator fun contains(request: DeepLinkRequest): Boolean = request in requests

	/**
	 * Records [request] as acted on. Null is accepted and ignored: the caller's "latest request" can
	 * legitimately be unset by the time a confirmation dialog is answered.
	 *
	 * Oldest-first eviction past [MAX_REMEMBERED] keeps the saved Bundle bounded against a sender
	 * that fires links in a loop. The evicted case degrades to the old behaviour -- one spurious
	 * reopen of a link superseded 32 links ago -- which no real sequence reaches.
	 */
	fun add(request: DeepLinkRequest?) {
		request ?: return
		requests += request
		while (requests.size > MAX_REMEMBERED) {
			requests.remove(requests.first())
		}
	}

	private companion object {
		const val MAX_REMEMBERED = 32
	}
}
