package com.itsaky.androidide.terminal

import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.UUID

/**
 * Runs plugin commands through the `agent-run.sh` script, which the session's bash executes. A
 * command is handed over in a file rather than on the command line, so it needs no quoting and the
 * line the session shows stays short.
 *
 * @param directory where the runner and the command files are written.
 * @param shellDirectory how a Terminal session's shell spells [directory].
 */
class AgentRunner(
	private val directory: File,
	private val shellDirectory: String = directory.path,
) {
	private val scriptPath get() = "$shellDirectory/$SCRIPT_NAME"

	/**
	 * Writes the runner, [command] and its [workingDirectory] where the runner reads them. Does file
	 * I/O, so call it off the main thread.
	 *
	 * @return the id the command runs under.
	 */
	fun prepare(
		command: String,
		workingDirectory: String?,
	): String {
		// Short, because a reused session shows it on the line that runs the command.
		val id = UUID.randomUUID().toString().take(ID_LENGTH)
		directory.mkdirs()
		File(directory, SCRIPT_NAME).writeText(script)
		File(directory, "$id.cmd").writeText(command)
		File(directory, "$id.dir").writeText(workingDirectory.orEmpty())
		return id
	}

	/**
	 * Deletes the files of command [id], which will never run; the runner deletes them once it
	 * reads them. Does file I/O, so call it off the main thread.
	 */
	fun discard(id: String) {
		File(directory, "$id.cmd").delete()
		File(directory, "$id.dir").delete()
	}

	/**
	 * The bash arguments of a new session: run command [id], then become a login shell for the
	 * commands after it. The INT trap keeps that shell alive when Ctrl-C stops the command.
	 */
	fun firstRunArguments(id: String): Array<String> = arrayOf("-c", "trap : INT; bash \"$scriptPath\" \"\$1\"; exec bash -l", "cogo", id)

	/** What is typed into an idle session to run command [id]: clear the prompt line, then run it. */
	fun typedRunLine(id: String): String = "${ControlKeys.CTRL_U}bash \"$scriptPath\" $id\r"

	companion object {
		private const val SCRIPT_NAME = "agent-run"
		private const val ID_LENGTH = 8

		/** The option on each of the runner's shell-integration marks naming the command. */
		const val ID_OPTION = "cogo-id"

		private val script: String by lazy {
			checkNotNull(AgentRunner::class.java.getResource("agent-run.sh")) { "agent-run.sh is missing" }.readText()
		}

		/** Runs commands from Termux's `$TMPDIR`, which Termux clears when its service stops. */
		val termux = AgentRunner(File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH), "\$TMPDIR")
	}
}

/** Control characters written to a session as if typed. */
internal object ControlKeys {
	/** Interrupts the foreground command. */
	const val CTRL_C = "\u0003"

	/** Clears what is typed at the prompt. */
	const val CTRL_U = "\u0015"
}
