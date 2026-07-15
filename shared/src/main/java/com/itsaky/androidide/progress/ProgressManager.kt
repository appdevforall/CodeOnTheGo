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

package com.itsaky.androidide.progress

import com.itsaky.androidide.progress.ICancelChecker.Default
import java.util.WeakHashMap
import java.util.concurrent.CancellationException

/**
 * @author Akash Yadav
 */
class ProgressManager private constructor() {

	private val threads = WeakHashMap<Thread, ICancelChecker>()

	companion object {

		val instance by lazy {
			ProgressManager()
		}

		@JvmStatic
		fun abortIfCancelled() {
			instance.abortIfCancelled()
		}
	}

	/**
	 * Associate an existing [checker] with [thread] so a later [cancel] of [thread] flips *this*
	 * checker (not a throwaway [Default]), letting a caller that polls [checker] observe the
	 * cancellation. Pair with [unregister].
	 *
	 * **Contract:** at most one live registration per thread. A caller must [unregister] its checker
	 * before registering another on the same thread. Any existing registration is overwritten and
	 * discarded, *including a cancelled one*: a [cancel] that arrived while nothing was registered
	 * targeted prior work on this thread, so it is not carried forward to the incoming [checker]. A
	 * caller that needs a cancel-before-register signal to survive must not rely on this method.
	 */
	fun register(thread: Thread, checker: ICancelChecker) {
		synchronized(threads) {
			threads[thread] = checker
		}
	}

	/** Remove any checker previously associated with [thread] via [register]. */
	fun unregister(thread: Thread) {
		synchronized(threads) {
			threads.remove(thread)
		}
	}

	fun cancel(thread: Thread) {
		synchronized(threads) {
			var checker = threads[thread]
			if (checker == null) {
				checker = Default()
				threads[thread] = checker
			}
			checker.cancel()
		}
	}

	@JvmName("internalAbortIfCancelled")
	private fun abortIfCancelled() {
		val thisThread = Thread.currentThread()
		// Check and remove atomically: a separate check-then-remove could race a concurrent register()
		// reusing this thread and delete the new, unrelated registration instead of the stale one.
		synchronized(threads) {
			val checker = threads[thisThread]
			if (checker != null && checker.isCancelled()) {
				threads.remove(thisThread)
				throw CancellationException()
			}
		}
	}
}