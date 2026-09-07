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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What the chart plots for a process that has gone away.
 *
 * The IDE and the tooling server live as long as the editor does, so this never mattered until the
 * Gradle daemon was plotted too (ADFA-5514): it is the one watched process that comes and goes, and
 * the biggest, so a stale reading for it is the most misleading of the three.
 */
@RunWith(RobolectricTestRunner::class)
class MemoryUsageWatcherLivenessTest {
	private val watchers = mutableListOf<MemoryUsageWatcher>()

	@After
	fun tearDown() {
		watchers.forEach { it.stopWatching() }
		watchers.clear()
	}

	private fun watcher() = MemoryUsageWatcher().also(watchers::add)

	private fun newestSample(
		watcher: MemoryUsageWatcher,
		pid: Int,
	): Long {
		val history = watcher.getMemoryUsage(pid)!!.usageHistory
		return history[history.size - 1]
	}

	@Test
	fun `a dead process plots zero rather than repeating its last reading`() {
		val watcher = watcher()
		watcher.watchProcess(DEAD_PID, "Gradle Daemon")

		// What the last successful sample left behind. Debug.getMemoryInfo leaves its output
		// untouched for a pid that no longer exists, so without a liveness check every later sample
		// reads this same figure back -- a flat line at 800MB for a daemon that has died, which is
		// worse than no line at all.
		watcher.getMemoryUsage(DEAD_PID)!!.memInfo.dalvikPss = STALE_PSS_KB
		watcher.isProcessAlive = { false }
		watcher.readUsages()

		assertThat(newestSample(watcher, DEAD_PID)).isEqualTo(0L)
	}

	@Test
	fun `liveness is decided per process, not for the sample as a whole`() {
		val watcher = watcher()
		watcher.watchProcess(DEAD_PID, "Gradle Daemon")
		watcher.watchProcess(LIVE_PID, "IDE")
		val asked = mutableListOf<Int>()

		watcher.isProcessAlive = { pid ->
			asked += pid
			pid == LIVE_PID
		}
		watcher.readUsages()

		// A dead daemon must not stop the IDE's own line being sampled.
		assertThat(asked).containsExactly(DEAD_PID, LIVE_PID)
		assertThat(newestSample(watcher, DEAD_PID)).isEqualTo(0L)
	}

	@Test
	fun `the default check really reads proc`() {
		val watcher = watcher()

		// Guards the tests above: they replace isProcessAlive wholesale, so nothing else here would
		// notice if the real one stopped answering.
		assertThat(watcher.isProcessAlive(LIVE_PID)).isTrue()
		assertThat(watcher.isProcessAlive(DEAD_PID)).isFalse()
	}

	private companion object {
		/** Above any pid the kernel will hand out, so `/proc` cannot have an entry for it. */
		const val DEAD_PID = Int.MAX_VALUE

		/** Stands in for the reading a dead process would otherwise repeat forever. */
		const val STALE_PSS_KB = 800 * 1024

		/**
		 * The test JVM itself, which is certainly alive.
		 *
		 * Read from `/proc/self`, which is the same source the check itself uses. Not
		 * `Process.myPid()`: Robolectric answers that with 0, which is not a pid this process has and
		 * collides with anything else standing in for "no such process".
		 */
		val LIVE_PID = File("/proc/self").canonicalFile.name.toInt()
	}
}
