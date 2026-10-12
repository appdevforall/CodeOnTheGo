package com.itsaky.androidide.terminal

import com.termux.shared.shell.ShellUtils
import com.termux.terminal.TerminalSession

/** What a plugin receives as the output of a command it ran in a visible Terminal session. */
internal object TerminalTranscript {
	// Anchored to the end so the command's own output can mention the banner text.
	private val EXIT_BANNER = Regex("""\[Process completed(?: \((?:code|signal) \d+\))? - press Enter]\s*$""")

	/** The finished [session]'s transcript, as the plugin should see it. */
	fun of(session: TerminalSession): String =
		stripExitBanner(ShellUtils.getTerminalSessionTranscriptText(session, true, false).orEmpty())

	/** Drops the "[Process completed ...]" line the terminal appends when the process exits. */
	fun stripExitBanner(transcript: String): String = transcript.replace(EXIT_BANNER, "").trimEnd()
}
