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
	 * Opens a new session in the visible Terminal, runs [command] there with bash, and suspends
	 * until it exits. The session stays open afterwards so the user can read what ran.
	 *
	 * [workingDirectory] is absolute or relative to the project root, and must lie inside it;
	 * null means the project root. Cancelling the calling coroutine kills the command.
	 *
	 * Requires the SYSTEM_COMMANDS permission.
	 *
	 * @throws SecurityException if the plugin lacks SYSTEM_COMMANDS, or [workingDirectory] is
	 *   outside the project root or given with no project open.
	 */
	suspend fun runInTerminal(
		command: String,
		workingDirectory: String? = null,
	): TerminalCommandResult
}

/**
 * Outcome of [IdeTerminalService.runInTerminal].
 */
sealed class TerminalCommandResult {
	/**
	 * The command ran and exited with [exitCode]. [output] is the session transcript: the echoed
	 * command line followed by stdout and stderr interleaved as the terminal showed them.
	 */
	data class Completed(
		val exitCode: Int,
		val output: String,
	) : TerminalCommandResult()

	/** The command never ran. [reason] says why, e.g. the terminal environment is not installed. */
	data class NotStarted(
		val reason: String,
	) : TerminalCommandResult()
}
