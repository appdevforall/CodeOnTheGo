package com.itsaky.androidide.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput

/**
 * Renders the bytes one command prints in an off-screen emulator of its own, as the session renders
 * them, so the command's output reads apart from the prompt and from earlier commands, however much
 * the session scrolls, clears or resizes.
 */
internal class CommandRecorder(
	columns: Int,
	rows: Int,
) : TerminalEmulator.OutputTap {
	private val emulator =
		TerminalEmulator(DiscardingOutput, columns.coerceAtLeast(MIN_COLUMNS), rows.coerceAtLeast(MIN_ROWS), TRANSCRIPT_ROWS, null)
	private val single = ByteArray(1)

	override fun onByte(b: Byte) {
		single[0] = b
		emulator.append(single, 1)
	}

	// Follows the session, so later output wraps and moves the cursor where the session's does.
	override fun onResize(
		columns: Int,
		rows: Int,
	) = emulator.resize(columns.coerceAtLeast(MIN_COLUMNS), rows.coerceAtLeast(MIN_ROWS))

	/** What the command printed so far. */
	fun output(): String =
		TerminalTranscript.stripExitBanner(emulator.screen.transcriptTextWithFullLinesJoined.orEmpty()).trim('\n').trimEnd()

	companion object {
		// Lines kept; a server's earlier log scrolls out like it does in the session.
		private const val TRANSCRIPT_ROWS = 2000
		private const val MIN_COLUMNS = 20
		private const val MIN_ROWS = 4

		/** A recorder the size of [emulator], so lines wrap where the session wraps them. */
		fun sizedLike(emulator: TerminalEmulator?) = CommandRecorder(emulator?.mColumns ?: 0, emulator?.mRows ?: 0)
	}

	// The command's terminal queries (cursor position, colours) are answered by the session, not here.
	private object DiscardingOutput : TerminalOutput() {
		override fun write(
			data: ByteArray,
			offset: Int,
			count: Int,
		) = Unit

		override fun titleChanged(
			oldTitle: String?,
			newTitle: String?,
		) = Unit

		override fun onCopyTextToClipboard(text: String?) = Unit

		override fun onPasteTextFromClipboard() = Unit

		override fun onBell() = Unit

		override fun onColorsChanged() = Unit
	}
}
