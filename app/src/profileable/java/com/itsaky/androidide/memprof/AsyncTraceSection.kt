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

package com.itsaky.androidide.memprof

import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * A slice on the Perfetto timeline, which may start and end on different threads.
 *
 * Each instance carries its own cookie, so overlapping slices with the same name still pair up.
 * Async slices need API 29; below that this records nothing.
 */
internal class AsyncTraceSection(
	name: String,
) {
	private val name = name.take(MAX_NAME_LENGTH)
	private val cookie = cookies.incrementAndGet()

	init {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Trace.beginAsyncSection(this.name, cookie)
		}
	}

	fun end() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Trace.endAsyncSection(name, cookie)
		}
	}

	private companion object {
		/** Keeps names within the 127-char limit atrace section names use. */
		const val MAX_NAME_LENGTH = 127

		val cookies = AtomicInteger()
	}
}
