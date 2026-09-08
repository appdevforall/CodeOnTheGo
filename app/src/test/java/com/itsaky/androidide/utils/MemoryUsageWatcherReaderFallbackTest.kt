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

package com.itsaky.androidide.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the sampler does when the cheap read fails (ADFA-5574).
 *
 * A rollup can be unreadable for reasons that are not the kernel's capability -- the process exited
 * between being listed and being read, most likely. The sampler must not lose the series over it,
 * and must not pay for the failure once a second for the rest of the session.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryUsageWatcherReaderFallbackTest {
	private var clock = 1_700_000_000_000L

	@Test
	fun `a read that fails latches the process onto the reflective one`() {
		var cheapAttempts = 0
		val alwaysUnavailable =
			ProcessMemoryReader { _, _ ->
				cheapAttempts++
				ProcessMemoryReaders.UNAVAILABLE
			}
		val watcher =
			MemoryUsageWatcher(
				nowMillis = {
					clock += TICK_MILLIS
					clock
				},
				readerFor = { alwaysUnavailable },
			)
		watcher.watchProcess(PID, "IDE")

		watcher.readUsages()
		watcher.readUsages()

		val proc = checkNotNull(watcher.getMemoryUsage(PID))
		assertThat(proc.reader).isSameInstanceAs(DebugMemoryInfoReader)

		// Once, not once per sample. The latch is the point: without it the sampler would try the
		// unreadable file every second and take the failure path every time.
		assertThat(cheapAttempts).isEqualTo(1)
	}

	private companion object {
		const val PID = 4242

		const val TICK_MILLIS = 1_000L
	}
}
