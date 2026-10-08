package com.itsaky.androidide.terminal

import com.termux.terminal.ShellIntegrationMark
import com.termux.terminal.TerminalSession

/**
 * Follows the shell-integration marks the runner prints in a plugin's sessions: starts recording
 * where a command's output starts, and reports through [onFinished] that it exited. Called on the
 * main thread, where the sessions deliver their output.
 */
internal class CommandMarkListener(
	private val pool: PluginSessionPool,
	private val onFinished: (session: PluginSession, exitCode: Int, runnerPid: Int?) -> Unit,
) : TerminalSession.ShellIntegrationListener {
	override fun onShellIntegrationMark(
		terminal: TerminalSession,
		mark: ShellIntegrationMark,
	) {
		val session = pool.find(terminal) ?: return
		val command = session.command ?: return
		// The user's own shell may print marks too; only the runner's carry this command's id.
		if (mark.options[AgentRunner.ID_OPTION] != command.id) return

		when (mark.kind) {
			ShellIntegrationMark.Kind.OUTPUT_START -> session.startRecording()
			ShellIntegrationMark.Kind.COMMAND_FINISHED ->
				onFinished(session, mark.exitCode ?: UNKNOWN_EXIT_CODE, mark.options[AgentRunner.PID_OPTION]?.toIntOrNull())
			ShellIntegrationMark.Kind.PROMPT_START, ShellIntegrationMark.Kind.COMMAND_START -> Unit
		}
	}

	companion object {
		/** The exit code reported when a command's end gives none. */
		const val UNKNOWN_EXIT_CODE = -1
	}
}
