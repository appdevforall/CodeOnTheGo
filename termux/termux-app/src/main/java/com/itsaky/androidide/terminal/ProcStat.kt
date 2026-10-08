package com.itsaky.androidide.terminal

import java.io.File

/** What the kernel says about the processes of a Terminal session. */
internal interface ProcessProbe {
	/** The foreground process group of the terminal that process [pid] controls, or null if it cannot be read. */
	fun foregroundProcessGroup(pid: Int): Int?

	/** The parent of process [pid], or null if it is gone or cannot be read. */
	fun parentOf(pid: Int): Int?
}

/** Answers [ProcessProbe] from `/proc/<pid>/stat`. */
internal class ProcStat(
	private val proc: File = File("/proc"),
) : ProcessProbe {
	override fun foregroundProcessGroup(pid: Int): Int? = field(pid, TPGID_FIELD)

	override fun parentOf(pid: Int): Int? = field(pid, PPID_FIELD)

	private fun field(
		pid: Int,
		index: Int,
	): Int? = runCatching { parse(File(proc, "$pid/stat").readText(), index) }.getOrNull()

	companion object {
		// Counted from the state field: state ppid pgrp session tty_nr tpgid
		const val PPID_FIELD = 1
		const val TPGID_FIELD = 5

		/** Field [index] of a `/proc/<pid>/stat` line, counted from the state, or null if it is not a positive number. */
		fun parse(
			stat: String,
			index: Int,
		): Int? {
			// The command name before the fields is in parentheses and may hold spaces and ')' itself.
			val fields = stat.substringAfterLast(')', "").trim().split(' ')
			return fields.getOrNull(index)?.toIntOrNull()?.takeIf { it > 0 }
		}
	}
}
