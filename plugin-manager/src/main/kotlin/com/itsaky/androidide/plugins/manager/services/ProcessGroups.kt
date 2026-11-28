package com.itsaky.androidide.plugins.manager.services

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** A signal [ProcessGroups] sends. [PROBE] delivers nothing; it only asks whether the group exists. */
internal enum class GroupSignal { PROBE, TERM, KILL }

/** A command line, and whether it runs as the leader of a process group of its own. */
internal data class GroupedCommand(
	val command: List<String>,
	val grouped: Boolean,
)

/**
 * Finds the first of [candidates] that runs commands in place, without making a command wait for it.
 * [current] answers from the checks done so far and starts one through [launch] when one could help, so
 * a check never runs on the path that starts a command, nor under its timeout. Meanwhile commands run
 * without a group of their own, as before the Terminal is installed.
 *
 * Each candidate's definite answer (in place, or forks) is kept, so it is checked once. A check that
 * could not tell, such as one that timed out on a busy device, is tried again, at most [maxUnsure]
 * times per candidate. One check runs at a time. A candidate that cannot run yet, as the Terminal's
 * before first-run setup, is checked once it can.
 *
 * @param check whether a candidate runs commands in place, or null if the check could not tell.
 */
internal class SetsidFinder(
	private val candidates: List<String>,
	private val check: (String) -> Boolean?,
	private val launch: (Runnable) -> Unit,
	private val maxUnsure: Int = MAX_UNSURE_CHECKS,
) {
	private val verdicts = ConcurrentHashMap<String, Boolean>()
	private val unsure = ConcurrentHashMap<String, Int>()
	private val checking = AtomicBoolean()

	/** The `setsid` to use now, or null while none is known to run commands in place. Never checks. */
	fun current(): String? {
		found()?.let { return it }
		val open = candidates.filter(::isOpen)
		if (open.isNotEmpty() && checking.compareAndSet(false, true)) {
			try {
				launch {
					try {
						checkAll(open)
					} finally {
						checking.set(false)
					}
				}
			} catch (e: Throwable) {
				// No thread for the check (out of memory): this command runs without a group, the next one tries again.
				checking.set(false)
				log.warn("Could not start the setsid check", e)
			}
		}
		// A launch that ran the check at once, as the unit tests' does, already has an answer.
		return found()
	}

	private fun found(): String? = candidates.firstOrNull { verdicts[it] == true && File(it).canExecute() }

	private fun isOpen(candidate: String): Boolean =
		!verdicts.containsKey(candidate) && (unsure[candidate] ?: 0) < maxUnsure && File(candidate).canExecute()

	private fun checkAll(open: List<String>) {
		for (candidate in open) {
			when (val inPlace = check(candidate)) {
				null -> {
					val tries = unsure.merge(candidate, 1, Int::plus)
					if (tries == maxUnsure) log.warn("{} could not be checked {} times; not checking it again", candidate, tries)
				}

				else -> {
					verdicts[candidate] = inPlace
					if (inPlace) return
				}
			}
		}
		if (candidates.none(::isOpen)) {
			log.warn("No setsid runs commands in place: a stop reaches only a command's own process ({})", candidates)
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(SetsidFinder::class.java)

		const val MAX_UNSURE_CHECKS = 3
	}
}

/**
 * Runs each shell command as the leader of a process group of its own, so that stopping it stops
 * every process it started, not only the one the IDE holds. Without a group, a command's children
 * (`a | b`, `cmd &`, pip's build steps) outlive it.
 *
 * @param setsidPath the `setsid` program, one that runs its command in place (see [runsInPlace]), so
 *   the process the IDE holds becomes the group's leader and its process id the group's. Null where
 *   there is none: commands then share the IDE's group, and only their own process can be stopped.
 * @param signal sends a [GroupSignal] to every process in group `pgid`, as `kill(-pgid, sig)` does.
 *   Returns false when no process is left in the group.
 */
internal class ProcessGroups(
	private val setsidPath: String?,
	private val signal: (pgid: Int, signal: GroupSignal) -> Boolean,
) {
	/** [command], changed to run in a group of its own when that is possible. */
	fun isolate(
		command: List<String>,
		workDir: File?,
	): GroupedCommand {
		val setsid = setsidPath ?: return GroupedCommand(command, grouped = false)
		// Without the program, start() fails as it always has, rather than setsid failing later.
		val program = programPath(command.first(), workDir) ?: return GroupedCommand(command, grouped = false)
		return GroupedCommand(listOf(setsid, program) + command.drop(1), grouped = true)
	}

	/**
	 * Stops [process] and, if it is [grouped], every process in its group: SIGTERM first, so a
	 * command such as pip can clean up, then SIGKILL for whatever is still there [KILL_AFTER_MS] later.
	 */
	suspend fun stop(
		process: Process,
		grouped: Boolean,
	) {
		val pgid = if (grouped) process.pidOrNull() else null
		if (grouped && pgid == null) log.warn("No process id for a command's group; stopping only its own process")
		var noGroupYet = false
		if (pgid != null) {
			var left = signal(pgid, GroupSignal.TERM)
			// Expected only for a stop landing before setsid ran; otherwise setsid did not make a group.
			noGroupYet = !left && process.isAlive
			if (noGroupYet) log.warn("No process group {} yet; stopping the command's own process", pgid)
			val deadline = System.nanoTime() + KILL_AFTER_MS * NANOS_PER_MILLI
			while (left && System.nanoTime() < deadline) {
				delay(POLL_MS)
				left = signal(pgid, GroupSignal.PROBE)
			}
			if (left) signal(pgid, GroupSignal.KILL)
		}
		// Also the whole story where there is no group.
		process.destroyForcibly()
		// setsid may have run since the TERM found no group, and the command started children in it.
		if (noGroupYet && pgid != null) signal(pgid, GroupSignal.KILL)
	}

	companion object {
		private val log = LoggerFactory.getLogger(ProcessGroups::class.java)

		const val KILL_AFTER_MS = 250L
		private const val POLL_MS = 20L
		private const val NANOS_PER_MILLI = 1_000_000L

		private const val ANDROID_SETSID = "/system/bin/setsid"
		private const val ANDROID_SH = "/system/bin/sh"
		private const val CHECK_TIMEOUT_S = 5L

		private val finders = ConcurrentHashMap<String, SetsidFinder>()

		/**
		 * Android's toybox `setsid`, or else [terminalSetsid], the util-linux one the Terminal installs,
		 * whichever first runs commands in place (see [runsInPlace]); and `kill(2)` through [Os]. The
		 * check runs on a thread of its own (see [SetsidFinder]): the Terminal installs its `setsid` at
		 * first-run setup, possibly after plugins have run commands, and where toybox's forks
		 * (Android 11) it is the only one that works.
		 */
		fun android(terminalSetsid: File): ProcessGroups = android(terminalSetsid, { runsInPlace(it, ANDROID_SH) }, ::checkOnOwnThread)

		/** [android], with [check] in place of running each candidate and [launch] in place of a thread. */
		internal fun android(
			terminalSetsid: File,
			check: (String) -> Boolean?,
			launch: (Runnable) -> Unit,
		): ProcessGroups {
			val finder =
				finders.getOrPut(terminalSetsid.path) { SetsidFinder(listOf(ANDROID_SETSID, terminalSetsid.path), check, launch) }
			return ProcessGroups(finder.current(), ::osSignal)
		}

		private fun checkOnOwnThread(check: Runnable) {
			thread(isDaemon = true, name = "setsid-check") { check.run() }
		}

		private fun osSignal(
			pgid: Int,
			signal: GroupSignal,
		): Boolean {
			val number =
				when (signal) {
					GroupSignal.PROBE -> 0
					GroupSignal.TERM -> OsConstants.SIGTERM
					GroupSignal.KILL -> OsConstants.SIGKILL
				}
			return try {
				Os.kill(-pgid, number)
				true
			} catch (e: ErrnoException) {
				// ESRCH: the group is empty.
				false
			}
		}

		/**
		 * Whether [setsid] runs [shell] in place, checked by running it once; null if the check could not
		 * tell (it timed out or failed to run). Toybox's `setsid`, like util-linux's, forks only when its
		 * caller leads a group, which a [ProcessBuilder] child never does, except in Android 11: there it
		 * makes itself a group leader first, so it always forks and exits 0 at once. Every command would
		 * then seem to end as it started, and its group would not be the one the IDE holds.
		 */
		internal fun runsInPlace(
			setsid: String,
			shell: String,
		): Boolean? {
			val inPlace =
				runCatching {
					val process = ProcessBuilder(setsid, shell, "-c", "echo \$\$").redirectErrorStream(true).start()
					if (!process.waitFor(CHECK_TIMEOUT_S, TimeUnit.SECONDS)) {
						process.destroyForcibly()
						log.warn("{} took over {} s to check", setsid, CHECK_TIMEOUT_S)
						return@runCatching null
					}
					val printed = process.inputStream.bufferedReader().use { it.readLine() }
					val pid = process.pidOrNull() ?: error("no process id for the check's process")
					printed != null && printed.trim() == pid.toString()
				}.getOrElse {
					log.warn("{} could not be checked ({})", setsid, it.toString())
					null
				}
			if (inPlace == false) log.warn("{} forks, so it is not used", setsid)
			return inPlace
		}
	}
}

/**
 * Where [name] is, as [ProcessBuilder] finds it: a bare name in the IDE's own PATH, which it uses
 * rather than the command's. `setsid` would search the command's, which puts Termux's first, so it
 * is given the path. Null if there is no such program.
 */
private fun programPath(
	name: String,
	workDir: File?,
): String? {
	val file =
		when {
			'/' !in name -> {
				System
					.getenv("PATH")
					.orEmpty()
					.split(':')
					.filter { it.isNotEmpty() }
					.map { File(it, name) }
					.firstOrNull { it.isFile && it.canExecute() }
			}

			File(name).isAbsolute -> {
				File(name)
			}

			else -> {
				File(workDir ?: File("").absoluteFile, name)
			}
		}
	return file?.takeIf { it.isFile && it.canExecute() }?.absolutePath
}

/**
 * The process id, which Android's API does not offer. Android's process class keeps it in a `pid`
 * field, which Termux's `ShellUtils.getPid` reads too; a desktop JVM, where the unit tests run, has
 * the `pid()` method instead.
 */
private fun Process.pidOrNull(): Int? =
	runCatching { javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(this) }.getOrNull()
		?: runCatching { (Process::class.java.getMethod("pid").invoke(this) as Long).toInt() }.getOrNull()
