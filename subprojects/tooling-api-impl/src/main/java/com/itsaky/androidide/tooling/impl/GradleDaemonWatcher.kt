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

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the Gradle daemon this server drives and reports it to the client.
 *
 * The daemon is the largest memory consumer of the three processes the IDE plots, and until
 * ADFA-5514 it was the one the memory chart could not show: the client has no handle on it. The
 * server does -- the daemon is its own child, which [Main.killDescendantProcesses] already relies on
 * to shut it down.
 *
 * The Tooling API spawns the daemon asynchronously, a moment after a build starts and only when
 * there is no reusable one already running, so there is no point in the build at which the pid can
 * simply be read. This polls for it over a bounded window instead, and stops as soon as it finds
 * one.
 */
internal class GradleDaemonWatcher(
	private val onStarted: (Int) -> Unit,
	private val onExited: (Int) -> Unit,
	private val descendants: () -> List<ProcessHandle> = {
		ProcessHandle.current().descendants().toList()
	},
	private val scheduler: ScheduledExecutorService = defaultScheduler(),
) {
	/** The daemon currently reported to the client, or [NO_PID] when there is none. */
	private val watched = AtomicInteger(NO_PID)

	/**
	 * Looks for a daemon, if one is not already being reported.
	 *
	 * Called when a build starts. Cheap and idempotent while a daemon is known: a daemon survives
	 * the build that spawned it and is reused by the next one, so the usual case is one scheduled
	 * task that reads an int and returns.
	 *
	 * The "is one known already" test is deliberately left to the poll rather than made here. Every
	 * change to [watched] happens on the scheduler, so asking there is asking after the exit of a
	 * daemon that has just died has been dealt with -- and a build starting in that window is
	 * exactly the case where the answer differs and a fresh daemon would otherwise go unplotted
	 * until the build after next.
	 */
	fun onBuildStarted() {
		var attempts = 0
		lateinit var poll: Runnable
		poll =
			Runnable {
				if (watched.get() != NO_PID) {
					return@Runnable
				}

				val found = runCatching { findDaemon(descendants()) }.getOrNull()
				if (found != null) {
					report(found)
					return@Runnable
				}

				if (++attempts < MAX_POLL_ATTEMPTS) {
					scheduler.schedule(poll, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
				} else {
					log.info("Gave up looking for a Gradle daemon after {} attempts", attempts)
				}
			}
		// Guarded: this one is submitted from the build's thread, and the scheduler rejects work
		// once [shutdown] has run. A build outliving the watcher must not fail over the chart.
		runCatching { scheduler.schedule(poll, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS) }
			.onFailure { err -> log.warn("Failed to schedule the Gradle daemon search", err) }
	}

	private fun report(handle: ProcessHandle) {
		val pid = handle.pid().toInt()
		if (!watched.compareAndSet(NO_PID, pid)) {
			return
		}

		log.info("Gradle daemon identified: pid {}", pid)
		runCatching { onStarted(pid) }
			.onFailure { err -> log.warn("Failed to report Gradle daemon {}", pid, err) }

		// The daemon is killed on server shutdown and can also die on its own -- an idle timeout, or
		// the platform reclaiming it under memory pressure, which on a small device is precisely the
		// case worth plotting. Either way the client has to be told, or it goes on charting a pid
		// that no longer exists.
		//
		// Hop onto the scheduler to say so. onExit runs on a process-reaper thread while starts are
		// reported from the poll, so the two could cross: freeing the slot is what lets the next
		// poll find a new daemon, and a start for the new one could reach the client before the
		// exit for the old one. The client would then be told to stop watching a daemon it had just
		// been told to start. Both reports come off one thread now, in order.
		handle.onExit().thenRun {
			runCatching { scheduler.execute { reportExit(pid) } }
				.onFailure { err -> log.warn("Failed to queue exit of Gradle daemon {}", pid, err) }
		}
	}

	private fun reportExit(pid: Int) {
		if (!watched.compareAndSet(pid, NO_PID)) {
			return
		}

		log.info("Gradle daemon {} exited", pid)
		runCatching { onExited(pid) }
			.onFailure { err -> log.warn("Failed to report exit of Gradle daemon {}", pid, err) }
	}

	/**
	 * Stops the poller.
	 *
	 * Graceful first, so a report already queued -- an exit picked up moments before the server was
	 * told to stop -- still runs; then forcefully, so a poll asleep between attempts cannot hold the
	 * process open. [SHUTDOWN_GRACE_MS] is the bound: work is one `ProcessHandle.descendants()`
	 * scan, not a build, so a poll that has not finished in that long is wedged rather than busy.
	 *
	 * A report that has *not* been submitted yet is lost, and that is accepted: it arrives via
	 * onExit on a process-reaper thread once the OS reaps the daemon, which at shutdown is after
	 * everything here has run.
	 */
	fun shutdown() {
		scheduler.shutdown()
		val drained =
			runCatching { scheduler.awaitTermination(SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS) }
				.onFailure { err ->
					// Only an interrupt is restored. Anything else out of awaitTermination says
					// nothing about this thread's cancellation state, and marking it interrupted
					// would abort the caller's next blocking call -- ToolingApiServerImpl.shutdown's
					// wait on the connection close -- over a failure unrelated to it.
					if (err is InterruptedException) {
						Thread.currentThread().interrupt()
					}
				}.getOrDefault(false)
		if (!drained) {
			scheduler.shutdownNow()
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(GradleDaemonWatcher::class.java)

		const val NO_PID = -1

		/**
		 * The daemon's main class, which is what tells it apart from any other JVM the build starts.
		 *
		 * Not "the only child": Gradle can run the Kotlin compiler in a daemon of its own, and that
		 * one is a sibling of this process rather than the one holding the build's heap.
		 */
		const val DAEMON_MAIN_CLASS = "org.gradle.launcher.daemon.bootstrap.GradleDaemon"

		private const val POLL_INTERVAL_MS = 500L

		/** How long [shutdown] lets queued reports finish before it stops waiting. */
		const val SHUTDOWN_GRACE_MS = 250L

		/** Bounded at roughly a minute, which is far longer than a daemon takes to come up. */
		const val MAX_POLL_ATTEMPTS = 120

		private fun defaultScheduler(): ScheduledExecutorService =
			Executors.newSingleThreadScheduledExecutor { runnable ->
				Thread(runnable, "GradleDaemonWatcher").apply { isDaemon = true }
			}

		/**
		 * The Gradle daemon among [candidates], or `null` if none of them is one.
		 */
		fun findDaemon(candidates: List<ProcessHandle>): ProcessHandle? =
			candidates.firstOrNull { handle ->
				handle.isAlive && isDaemonCommandLine(commandLineOf(handle))
			}

		fun isDaemonCommandLine(commandLine: String?): Boolean = commandLine?.contains(DAEMON_MAIN_CLASS) == true

		/**
		 * The command line of [handle], as a single string.
		 *
		 * `ProcessHandle.info()` is the portable route, but it reads the command line through the
		 * platform's own process listing and comes back empty often enough -- for processes it
		 * considers foreign, and on restricted systems -- that it cannot be the only one. `/proc` is
		 * authoritative here, and readable: the daemon is a child of this process and runs under the
		 * same uid.
		 */
		private fun commandLineOf(handle: ProcessHandle): String? {
			val info = runCatching { handle.info().commandLine().orElse(null) }.getOrNull()
			if (!info.isNullOrBlank()) {
				return info
			}
			return runCatching {
				// Arguments are NUL-separated in /proc, so they have to be joined back up before
				// anything can be matched across them.
				File("/proc/${handle.pid()}/cmdline")
					.readBytes()
					.toString(Charsets.UTF_8)
					.replace('\u0000', ' ')
			}.getOrNull()
		}
	}
}
