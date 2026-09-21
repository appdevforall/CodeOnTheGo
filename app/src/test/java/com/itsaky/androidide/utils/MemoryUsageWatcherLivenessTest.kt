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
import org.junit.Assume.assumeTrue
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

		// The control, and it is not optional: a zero on its own proves nothing, because a zero is
		// also what a watcher that read nothing at all would hold. Called alive, the same setup
		// reads the stale figure back -- so the zero below is a decision rather than a default.
		watcher.isProcessAlive = { true }
		watcher.readUsages()
		assertThat(newestSample(watcher, DEAD_PID)).isEqualTo(STALE_PSS_KB * 1024L)

		watcher.isProcessAlive = { false }
		watcher.readUsages()

		assertThat(newestSample(watcher, DEAD_PID)).isEqualTo(0L)
	}

	@Test
	fun `liveness is decided per process, not for the sample as a whole`() {
		val watcher = watcher()
		watcher.watchProcess(DEAD_PID, "Gradle Daemon")
		watcher.watchProcess(OTHER_PID, "IDE")
		val asked = mutableListOf<Int>()

		watcher.isProcessAlive = { pid ->
			asked += pid
			pid == OTHER_PID
		}
		watcher.readUsages()

		// A dead daemon must not stop the IDE's own line being sampled.
		assertThat(asked).containsExactly(DEAD_PID, OTHER_PID)
		assertThat(newestSample(watcher, DEAD_PID)).isEqualTo(0L)
	}

	@Test
	fun `a process unwatched while it is being sampled does not take the sampler down with it`() {
		val watcher = watcher()
		watcher.watchProcess(DEAD_PID, "Gradle Daemon")
		watcher.watchProcess(OTHER_PID, "IDE")

		// The daemon is unwatched from a build event, on the main thread, while readUsages runs on
		// the sampling thread. Reading the map twice per process left a window between the two in
		// which the entry could be dropped, and the second read asserted it was there.
		watcher.isProcessAlive = { pid ->
			if (pid == DEAD_PID) {
				watcher.unwatchProcess(DEAD_PID)
			}
			true
		}

		watcher.readUsages()

		assertThat(watcher.getMemoryUsage(DEAD_PID)).isNull()
		assertThat(watcher.getMemoryUsage(OTHER_PID)).isNotNull()
	}

	@Test
	fun `unwatching a daemon by pid leaves the one that replaced it alone`() {
		val watcher = watcher()
		watcher.watchProcess(DEAD_PID, "Gradle Daemon")

		// A new build starts a new daemon. watchProcess is unique by name, so the old pid is gone
		// from the map before its exit is even reported.
		watcher.watchProcess(OTHER_PID, "Gradle Daemon")

		// The exit of the old one arrives afterwards, which is the order the tooling server's
		// reaper thread and its poll can produce. By pid this is a no-op; by name it would blank
		// the line for the daemon that is actually running.
		watcher.unwatchProcess(DEAD_PID)

		assertThat(watcher.getMemoryUsage(OTHER_PID)).isNotNull()
	}

	@Test
	fun `the default check really reads proc`() {
		// Guards the tests above: they replace isProcessAlive wholesale, so nothing else here would
		// notice if the real one stopped answering.
		//
		// This is the only test that needs a pid `/proc` really has, so it reads one here rather
		// than in a companion initialiser. There it took the whole class down with an
		// ExceptionInInitializerError on any platform without `/proc` -- four unrelated tests
		// failing for a reason none of them is about -- instead of skipping the one that cares.
		val selfPid = File("/proc/self").canonicalFile.name.toLongOrNull()
		assumeTrue("no /proc on this platform", selfPid != null)

		val watcher = watcher()
		assertThat(watcher.isProcessAlive(selfPid!!.toInt())).isTrue()
		assertThat(watcher.isProcessAlive(DEAD_PID)).isFalse()
	}

	private companion object {
		/** Above any pid the kernel will hand out, so `/proc` cannot have an entry for it. */
		const val DEAD_PID = Int.MAX_VALUE

		/** Stands in for the reading a dead process would otherwise repeat forever. */
		const val STALE_PSS_KB = 800 * 1024

		/**
		 * A second watched process.
		 *
		 * Any number will do: every test that uses it replaces [MemoryUsageWatcher.isProcessAlive],
		 * so nothing asks `/proc` about it. Not `Process.myPid()`, which Robolectric answers with 0
		 * -- not a pid this process has, and a collision with anything standing in for "no such
		 * process".
		 */
		const val OTHER_PID = 4243
	}
}
