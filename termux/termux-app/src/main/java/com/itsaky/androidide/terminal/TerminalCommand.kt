package com.itsaky.androidide.terminal

import com.termux.terminal.TerminalSession

/** Why a plugin's command never ran in the Terminal. */
sealed class TerminalStartFailure(
	val message: String,
) {
	data object NotInForeground : TerminalStartFailure("Code On the Go is not in the foreground")

	data object TerminalNotOpened : TerminalStartFailure("The Terminal could not be opened")

	data object TerminalDidNotOpen : TerminalStartFailure("The Terminal did not open")

	data object TerminalClosed : TerminalStartFailure("The Terminal closed before the command could start")

	data object SessionNotCreated : TerminalStartFailure("The terminal session could not be started")

	data class AllSessionsBusy(
		val sessionNames: List<String>,
	) : TerminalStartFailure(
			"Every Terminal session of this plugin is still running a command " +
				"(${sessionNames.joinToString()}); stop one of them first",
		)
}

/** What happens to a plugin's command; called on the main thread, each at most once. */
interface TerminalCommandListener {
	/** A Terminal session named [sessionName] took the command. */
	fun onStarted(sessionName: String)

	/** The command exited with [exitCode]; [output] is what it printed. */
	fun onExited(
		exitCode: Int,
		output: String,
	)

	/** The command never ran. */
	fun onNotStarted(reason: TerminalStartFailure)
}

/** The last command in one of a plugin's Terminal sessions. */
sealed interface CommandState {
	val sessionName: String
	val output: String

	data class Running(
		override val sessionName: String,
		override val output: String,
	) : CommandState

	data class Exited(
		override val sessionName: String,
		val exitCode: Int,
		override val output: String,
	) : CommandState
}

/** A command a plugin asked to run in the Terminal, from the moment it is queued until it ends. */
class TerminalCommand internal constructor(
	val id: String,
	val workingDirectory: String?,
	/** The plugin that asked; its sessions are the only ones this command may run in. */
	val owner: String,
	internal val listener: TerminalCommandListener,
	/** What a session opened for it is named after, e.g. the plugin's display name. */
	val sessionLabel: String = owner,
) {
	internal var state: State = State.Queued

	internal sealed interface State {
		/** Waiting for the Terminal to claim it. */
		data object Queued : State

		/** Claimed by the Terminal, which has not started it yet. */
		data object Claimed : State

		/** Cancelled after it was claimed and before it started; it never will. */
		data object Cancelled : State

		data class Running(
			val session: TerminalSession,
		) : State

		/** Exited, or never started; the listener has been told. */
		data object Ended : State
	}
}
