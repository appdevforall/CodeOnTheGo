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

	/** For `onSaveInstanceState`; pairs with [restore]. */
	fun toSavedList(): ArrayList<T> = ArrayList(requests)

	/** Replaces the contents with [saved], which is null when there is no instance state to restore. */
	fun restore(saved: List<T>?) {
		requests.clear()
		saved?.let(requests::addAll)
	}

	operator fun contains(request: T): Boolean = request in requests

	/**
	 * Forgets [request], so an equal-by-value request deliberately re-armed by the caller (e.g. the
	 * same file/line navigation requested a second time, parked on the Intent for a deferred apply)
	 * is not mistaken for the already-consumed earlier one and silently skipped.
	 */
	fun remove(request: T) {
		requests.remove(request)
	}

	/**
	 * Records [request] as acted on. Null is accepted and ignored: the caller's "latest request" can
	 * legitimately be unset by the time a confirmation dialog is answered.
	 *
	 * Oldest-first eviction past [MAX_REMEMBERED] keeps the saved Bundle bounded against a sender
	 * that fires links in a loop. The evicted case degrades to the old behaviour -- one spurious
	 * reopen of a link superseded 32 links ago -- which no real sequence reaches.
	 */
	fun add(request: T?) {
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
