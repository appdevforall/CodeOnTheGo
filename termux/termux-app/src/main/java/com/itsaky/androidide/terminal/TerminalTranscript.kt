package com.itsaky.androidide.terminal

/** Shapes a command's Terminal output for the plugin that ran it. */
internal object TerminalTranscript {
	// Anchored to the end so the command's own output can mention the banner text.
	private val EXIT_BANNER = Regex("""\[Process completed(?: \((?:code|signal) \d+\))? - press Enter]\s*$""")

	/** Drops the "[Process completed ...]" line the terminal appends when the process exits. */
	fun stripExitBanner(transcript: String): String = transcript.replace(EXIT_BANNER, "").trimEnd()
}
