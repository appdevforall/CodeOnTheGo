package com.itsaky.androidide.terminal

import java.io.File

/** Reads which process group holds a terminal's foreground, from the kernel's view of its shell. */
internal object ForegroundProcessGroup {
	/**
	 * The foreground process group of the terminal that process [pid] controls, or null if it cannot
	 * be read.
	 */
	fun of(
		pid: Int,
		proc: File = File("/proc"),
	): Int? = runCatching { parse(File(proc, "$pid/stat").readText()) }.getOrNull()

	/** The `tpgid` field of a `/proc/<pid>/stat` line, or null if [stat] is not one. */
	fun parse(stat: String): Int? {
		// The command name before the fields is in parentheses and may hold spaces and ')' itself.
		val fields = stat.substringAfterLast(')', "").trim().split(' ')
		// state ppid pgrp session tty_nr tpgid
		return fields.getOrNull(TPGID_FIELD)?.toIntOrNull()?.takeIf { it > 0 }
	}

	private const val TPGID_FIELD = 5
}
