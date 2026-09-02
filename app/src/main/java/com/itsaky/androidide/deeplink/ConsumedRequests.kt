/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.deeplink

import android.os.Parcelable

/**
 * The deep-link-style requests (e.g. [com.itsaky.androidide.models.DeepLinkRequest],
 * [com.itsaky.androidide.models.PendingFileRequest]) this task has already acted on, so a
 * redelivered Intent carrying one of them does not force its navigation a second time (ADFA-5067).
 * `Intent.removeExtra` alone cannot provide this: it mutates only this process's Intent object,
 * while a recreate after process death is handed the system's *parceled* copy, extras intact --
 * which is why consumers persist this set through `onSaveInstanceState`.
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
class ConsumedRequests<T : Parcelable> {
	private val requests = LinkedHashSet<T>()

	/**
	 * The launch-Intent entry: the first request this set ever recorded, and the one Android replays
	 * verbatim after process death. Held by value rather than by position because [add] reorders the
	 * set to track recency -- a re-add used to slide this entry off slot 0, leaving the positional
	 * pin in [add]'s eviction loop protecting whichever arbitrary request happened to land there.
	 */
	private var pinned: T? = null

	/** For `onSaveInstanceState`; pairs with [restore]. [pinned] leads, so [restore] can re-establish it. */
	fun toSavedList(): ArrayList<T> {
		val saved = ArrayList<T>(requests.size)
		pinned?.let(saved::add)
		requests.filterTo(saved) { it != pinned }
		return saved
	}

	/**
	 * Replaces the contents with [saved], which is null when there is no instance state to restore.
	 *
	 * [MAX_REMEMBERED] is re-applied here: the cap exists to bound the saved Bundle against a sender
	 * firing links in a loop, and restoring an over-long list unchecked would carry an oversized set
	 * straight back into the next `onSaveInstanceState`.
	 */
	fun restore(saved: List<T>?) {
		requests.clear()
		pinned = saved?.firstOrNull()
		saved?.let(requests::addAll)
		evictExcess()
	}

	operator fun contains(request: T): Boolean = request in requests

	/**
	 * Forgets [request], so an equal-by-value request deliberately re-armed by the caller (e.g. the
	 * same file/line navigation requested a second time, parked on the Intent for a deferred apply)
	 * is not mistaken for the already-consumed earlier one and silently skipped.
	 */
	fun remove(request: T) {
		requests.remove(request)
		// Dropping the pinned entry releases the pin too, so the next add() re-establishes it rather
		// than leaving a pin on a request no longer in the set -- which would exempt nothing and let
		// eviction reach the real launch entry again.
		if (pinned == request) {
			pinned = null
		}
	}

	/**
	 * Records [request] as acted on. Null is accepted and ignored: the caller's "latest request" can
	 * legitimately be unset by the time a confirmation dialog is answered.
	 *
	 * Eviction past [MAX_REMEMBERED] keeps the saved Bundle bounded against a sender that fires links
	 * in a loop -- but it deliberately does NOT evict [pinned], the request on the task's launch
	 * Intent. That is the one entry this class exists to remember, since it is the Intent Android
	 * replays verbatim after process death; evicting it force-reopened its project over whatever the
	 * user was doing, the regression this class was written to prevent.
	 *
	 * A re-add refreshes an entry's position so "oldest" tracks use rather than first sighting --
	 * except for [pinned], which is left where it is. Reordering it was the bug: the pin used to be
	 * positional (slot 0), so re-tapping the same URL, or [remove] followed by [add] on a re-armed
	 * request, slid the launch entry down and handed its protection to an unrelated request.
	 */
	fun add(request: T?) {
		request ?: return
		if (pinned == null) {
			pinned = request
		}
		// Re-insert so position tracks recency: `requests += request` leaves an existing element where
		// it was, which made a repeatedly-seen request look like the least recent. Skipped for the
		// pinned entry, whose position no longer carries meaning and must stay stable for [toSavedList].
		if (request != pinned) {
			requests.remove(request)
		}
		requests += request
		evictExcess()
	}

	/** Drops the oldest non-[pinned] entries until the set is back within [MAX_REMEMBERED]. */
	private fun evictExcess() {
		while (requests.size > MAX_REMEMBERED) {
			// firstOrNull, not first(): a set of nothing but the pinned entry has no eligible victim,
			// and looping forever on it would hang whichever lifecycle callback got here.
			val victim = requests.firstOrNull { it != pinned } ?: return
			requests.remove(victim)
		}
	}

	private companion object {
		const val MAX_REMEMBERED = 32
	}
}
