package com.itsaky.androidide.plugins.services

/**
 * Service interface for the IDE's terminal environment (bash and the packages installed with it).
 *
 * Use [IdeCommandService] to run a command headless; use [runInTerminal] when the user should see
 * what ran. Available since 26.41.
 */
interface IdeTerminalService {
	/**
	 * True when the terminal environment is installed and bash starts and exits cleanly. Starts a
	 * process, so call it off the main thread. Needs no permission.
	 */
	suspend fun isTerminalReady(): Boolean

	/**
	 * Runs [command] with bash in the visible Terminal and suspends until it exits or [waitMillis]
	 * pass. Each plugin keeps a few Terminal sessions and reuses an idle one; a new session opens
	 * only while every other one is still running a command, such as a dev server.
	 *
	 * Each command runs in its own bash process started in [workingDirectory], so a `cd` or `export`
	 * does not carry over to the next command. [workingDirectory] is absolute or relative to the
	 * project root, and must lie inside it; null means the project root.
	 *
	 * Cancelling the calling coroutine interrupts the command with Ctrl-C. After [waitMillis] the
	 * command keeps running and [TerminalCommandResult.Running] is returned.
	 *
	 * Requires the SYSTEM_COMMANDS permission.
	 *
	 * @throws SecurityException if the plugin lacks SYSTEM_COMMANDS, or [workingDirectory] is
	 *   outside the project root or given with no project open.
	 */
	suspend fun runInTerminal(
		command: String,
		workingDirectory: String? = null,
		waitMillis: Long = DEFAULT_WAIT_MILLIS,
	): TerminalCommandResult

	/**
	 * The last command run in this plugin's Terminal session [sessionName], the name a
	 * [TerminalCommandResult.Running] carries: [TerminalCommandResult.Running] with its output so
	 * far while it runs, [TerminalCommandResult.Completed] once it exits. Null when the plugin has
	 * no open session by that name; another plugin's sessions and the user's own are never read.
	 *
	 * Requires the SYSTEM_COMMANDS permission.
	 *
	 * @throws SecurityException if the plugin lacks SYSTEM_COMMANDS.
	 */
	suspend fun readSession(sessionName: String): TerminalCommandResult?

	/**
	 * Interrupts the command running in this plugin's Terminal session [sessionName] with Ctrl-C,
	 * as the user would, and waits up to [waitMillis] for it to exit. Returns the session's state
	 * afterwards, as [readSession] does: [TerminalCommandResult.Completed] once it exited,
	 * [TerminalCommandResult.Running] if it ignored the interrupt, null when the plugin has no open
	 * session by that name. A command that already exited is left alone.
	 *
	 * Requires the SYSTEM_COMMANDS permission.
	 *
	 * @throws SecurityException if the plugin lacks SYSTEM_COMMANDS.
	 */
	suspend fun stopSession(
		sessionName: String,
		waitMillis: Long = DEFAULT_STOP_WAIT_MILLIS,
	): TerminalCommandResult?

	companion object {
		/** How long [runInTerminal] waits for a command to exit unless told otherwise. */
		const val DEFAULT_WAIT_MILLIS: Long = 30_000L

		/** How long [stopSession] waits for an interrupted command to exit unless told otherwise. */
		const val DEFAULT_STOP_WAIT_MILLIS: Long = 5_000L
	}
}

/**
 * Outcome of [IdeTerminalService.runInTerminal], and state of [IdeTerminalService.readSession].
 */
sealed class TerminalCommandResult {
	/**
	 * The command ran and exited with [exitCode]. [output] is what the command printed in the
	 * session: the echoed command line followed by stdout and stderr interleaved as the terminal
	 * showed them. Earlier commands in the same session are not included.
	 */
	data class Completed(
		val exitCode: Int,
		val output: String,
	) : TerminalCommandResult()

	/**
	 * The command was still running when the wait ended, and keeps running in the Terminal session
	 * named [sessionName] until it exits or the user stops it. [output] is what it printed so far.
	 */
	data class Running(
		val sessionName: String,
		val output: String,
	) : TerminalCommandResult()

	/** The command never ran. [reason] says why, e.g. the terminal environment is not installed. */
	data class NotStarted(
		val reason: String,
	) : TerminalCommandResult()
}
