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

package com.itsaky.androidide.tooling.impl

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Which of the tooling server's children is the Gradle daemon, and when the client hears about it.
 *
 * Measured on a Pixel 6 Pro during a build: the IDE at 702 MB, the tooling server at 165 MB and the
 * daemon at 779 MB -- the largest of the three, and the one the chart could not show (ADFA-5514).
 */
@RunWith(JUnit4::class)
class GradleDaemonWatcherTest {
	private val started = mutableListOf<Int>()
	private val exited = mutableListOf<Int>()

	/**
	 * A handle whose command line is what a real daemon's looks like on device.
	 *
	 * The full line carries the daemon jar and its heap settings; the main class is the part that
	 * identifies it.
	 */
	private fun handle(
		pid: Long,
		commandLine: String?,
		alive: Boolean = true,
		exit: CompletableFuture<ProcessHandle> = CompletableFuture(),
	): ProcessHandle {
		val info = mockk<ProcessHandle.Info>()
		every { info.commandLine() } returns Optional.ofNullable(commandLine)
		return mockk<ProcessHandle>().also {
			every { it.pid() } returns pid
			every { it.isAlive } returns alive
			every { it.info() } returns info
			every { it.onExit() } returns exit
		}
	}

	private fun daemon(
		pid: Long,
		exit: CompletableFuture<ProcessHandle> = CompletableFuture(),
	) = handle(pid, DAEMON_COMMAND_LINE, exit = exit)

	/** Runs whatever is scheduled straight away, so a test does not have to wait out the poll. */
	private fun immediateScheduler(): ScheduledExecutorService =
		mockk<ScheduledExecutorService>(relaxed = true).also { scheduler ->
			every { scheduler.schedule(any<Runnable>(), any(), any<TimeUnit>()) } answers {
				firstArg<Runnable>().run()
				mockk(relaxed = true)
			}
		}

	private fun watcher(
		vararg children: ProcessHandle,
		scheduler: ScheduledExecutorService = immediateScheduler(),
	) = GradleDaemonWatcher(
		onStarted = started::add,
		onExited = exited::add,
		descendants = { children.toList() },
		scheduler = scheduler,
	)

	@Test
	fun `the daemon is picked out of the server's other children`() {
		// The Kotlin compiler can run in a daemon of its own, a sibling of the Gradle daemon rather
		// than the process holding the build's heap. Taking "the only child" would pick either.
		val kotlinDaemon = handle(2L, "/usr/bin/java -cp kotlin-daemon.jar org.jetbrains.kotlin.daemon.KotlinCompileDaemon")
		val gradleDaemon = daemon(3L)

		watcher(kotlinDaemon, gradleDaemon).onBuildStarted()

		assertThat(started).containsExactly(3)
	}

	@Test
	fun `a build with no daemon of its own reports nothing`() {
		watcher(handle(2L, "/usr/bin/java -jar something-else.jar")).onBuildStarted()

		assertThat(started).isEmpty()
	}

	@Test
	fun `a dead child is not reported, however it identifies itself`() {
		val corpse = handle(3L, DAEMON_COMMAND_LINE, alive = false)

		watcher(corpse).onBuildStarted()

		assertThat(started).isEmpty()
	}

	@Test
	fun `the daemon is reported once, not once per build`() {
		val watcher = watcher(daemon(3L))

		watcher.onBuildStarted()
		watcher.onBuildStarted()
		watcher.onBuildStarted()

		// A daemon outlives the build that spawned it and is reused by the next one. Reporting it
		// again would re-watch a pid already being plotted.
		assertThat(started).containsExactly(3)
	}

	@Test
	fun `the client is told when the daemon exits`() {
		val exit = CompletableFuture<ProcessHandle>()
		val handle = daemon(3L, exit = exit)

		watcher(handle).onBuildStarted()
		exit.complete(handle)

		assertThat(exited).containsExactly(3)
	}

	@Test
	fun `a daemon replacing one that exited is reported in its turn`() {
		val firstExit = CompletableFuture<ProcessHandle>()
		val first = daemon(3L, exit = firstExit)
		var children = listOf(first)
		val watcher =
			GradleDaemonWatcher(
				onStarted = started::add,
				onExited = exited::add,
				descendants = { children },
				scheduler = immediateScheduler(),
			)

		watcher.onBuildStarted()
		firstExit.complete(first)
		children = listOf(daemon(4L))
		watcher.onBuildStarted()

		// Gradle starts a fresh daemon when the old one is gone -- after an idle timeout, or after
		// the platform reclaimed it, which on a small device is the case worth plotting.
		assertThat(started).containsExactly(3, 4).inOrder()
		assertThat(exited).containsExactly(3)
	}

	@Test
	fun `the search gives up rather than polling for the life of the server`() {
		val scheduler = immediateScheduler()

		watcher(handle(2L, "/usr/bin/java -jar not-a-daemon.jar"), scheduler = scheduler).onBuildStarted()

		assertThat(started).isEmpty()
		// Bounded: one initial schedule plus the retries, and no more.
		verify(atMost = MAX_SCHEDULES) {
			scheduler.schedule(any<Runnable>(), any(), any<TimeUnit>())
		}
	}

	private companion object {
		/** What the daemon's command line looks like on device, trimmed to the identifying part. */
		const val DAEMON_COMMAND_LINE =
			"/usr/bin/java -Xmx4620m -XX:MaxMetaspaceSize=384m -cp " +
				"/data/data/com.itsaky.androidide/files/home/.cg/gradle-dists/gradle-8.14.3/lib/" +
				"gradle-daemon-main-8.14.3.jar " +
				GradleDaemonWatcher.DAEMON_MAIN_CLASS + " 8.14.3"

		/** Every poll attempt, plus the initial schedule. */
		const val MAX_SCHEDULES = GradleDaemonWatcher.MAX_POLL_ATTEMPTS + 1
	}
}
