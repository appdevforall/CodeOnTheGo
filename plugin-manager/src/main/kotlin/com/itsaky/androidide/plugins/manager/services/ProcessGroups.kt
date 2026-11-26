package com.itsaky.androidide.plugins.manager.services

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A signal [ProcessGroups] sends. [PROBE] delivers nothing; it only asks whether the group exists. */
internal enum class GroupSignal { PROBE, TERM, KILL }

/** A command line, and whether it runs as the leader of a process group of its own. */
internal data class GroupedCommand(
	val command: List<String>,
	val grouped: Boolean,
)

/**
 * What checking the `setsid` candidates found: [path], the first that runs commands in place, or null
 * if none does. Not [definite] when a check could not tell (it timed out or failed to run), so the
 * answer is not kept and the next command checks again.
 */
internal data class SetsidCheck(
	val path: String?,
	val definite: Boolean,
)

/**
 * Runs each shell command as the leader of a process group of its own, so that stopping it stops
 * every process it started, not only the one the IDE holds. Without a group, a command's children
 * (`a | b`, `cmd &`, pip's build steps) outlive it.
 *
 * @param setsidPath the `setsid` program, one that runs its command in place (see [usableSetsid]), so
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

		private val android = ConcurrentHashMap<String, ProcessGroups>()

		/**
		 * Android's toybox `setsid`, or else [terminalSetsid], the util-linux one the Terminal installs,
		 * whichever first runs commands in place (see [usableSetsid]); and `kill(2)` through [Os].
		 * Checked once per [terminalSetsid], unless neither works while [terminalSetsid] cannot run
		 * yet: the Terminal installs it at first-run setup, possibly after plugins have run commands,
		 * and where toybox's forks (Android 11) it is the only one that works. Also checked again
		 * after a check that could not tell, such as one that timed out on a busy device.
		 */
		fun android(terminalSetsid: File): ProcessGroups =
			android(terminalSetsid) { usableSetsid(listOf(ANDROID_SETSID, terminalSetsid.path), ANDROID_SH) }

		/** [android], with [findSetsid] in place of checking the candidates. */
		internal fun android(
			terminalSetsid: File,
			findSetsid: () -> SetsidCheck,
		): ProcessGroups =
			android[terminalSetsid.path] ?: synchronized(android) {
				android[terminalSetsid.path] ?: run {
					val found = findSetsid()
					val groups = ProcessGroups(found.path, ::osSignal)
					val kept = found.path != null || (found.definite && terminalSetsid.canExecute())
					if (kept) android[terminalSetsid.path] = groups
					groups
				}
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
		 * The first of [candidates] that runs its command in place, which is checked by running [shell]
		 * under it once; null, with a warning, if none does. Toybox's `setsid`, like util-linux's, forks
		 * only when its caller leads a group, which a [ProcessBuilder] child never does, except in
		 * Android 11: there it makes itself a group leader first, so it always forks and exits 0 at
		 * once. Every command would then seem to end as it started, and its group would not be the one
		 * the IDE holds.
		 */
		fun usableSetsid(
			candidates: List<String>,
			shell: String,
		): SetsidCheck {
			var definite = true
			for (candidate in candidates.filter { File(it).canExecute() }) {
				val inPlace = runsInPlace(candidate, shell)
				if (inPlace == true) return SetsidCheck(candidate, definite = true)
				if (inPlace == null) definite = false
			}
			log.warn("No setsid runs commands in place: a stop reaches only a command's own process ({})", candidates)
			return SetsidCheck(null, definite)
		}

		/** Whether [setsid] runs [shell] in place, or null if the check could not tell. */
		private fun runsInPlace(
			setsid: String,
			shell: String,
		): Boolean? {
			val inPlace =
				runCatching {
					val process = ProcessBuilder(setsid, shell, "-c", "echo \$\$").redirectErrorStream(true).start()
					if (!process.waitFor(CHECK_TIMEOUT_S, TimeUnit.SECONDS)) {
						process.destroyForcibly()
						log.warn("{} took over {} s to check; checking it again on the next command", setsid, CHECK_TIMEOUT_S)
						return@runCatching null
					}
					val printed = process.inputStream.bufferedReader().use { it.readLine() }
					val pid = process.pidOrNull() ?: error("no process id for the check's process")
					printed != null && printed.trim() == pid.toString()
				}.getOrElse {
					log.warn("{} could not be checked ({}); checking it again on the next command", setsid, it.toString())
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
