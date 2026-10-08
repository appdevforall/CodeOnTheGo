package com.itsaky.androidide.terminal

import com.termux.terminal.TerminalSession

/** Opens Terminal sessions for plugin commands; implemented by the Terminal screen. */
interface TerminalSessionFactory {
	/** Whether [session] is still open and its shell running, so it can take another command. */
	fun isOpen(session: TerminalSession): Boolean

	/** The process group in the foreground of [session]'s terminal, or null if that cannot be told. */
	fun foregroundProcessGroup(session: TerminalSession): Int?

	/** Opens a visible session named [name] running bash with [bashArguments], or null if it cannot. */
	fun open(
		name: String,
		bashArguments: Array<String>,
		workingDirectory: String?,
	): TerminalSession?
}

/**
 * A Terminal session a plugin opened: the command it runs now, and what that printed.
 *
 * @param name what the session was opened as, which the plugin addresses it by even after the user
 *   renames it.
 */
internal class PluginSession(
	val terminal: TerminalSession,
	val name: String,
) {

	/** The command running here, or null while the session is idle. */
	var command: TerminalCommand? = null
		private set

	val isIdle: Boolean get() = command == null

	/** Whether the running command's run line was typed at the prompt, rather than given to a new session. */
	var typed = false
		private set

	/** When the running command was handed to the session. */
	var startedAt = 0L
		private set

	/**
	 * The runner of the running command once it reported in, else of the last command, which keeps
	 * the foreground for a moment after it reports its end.
	 */
	var runnerPid: Int? = null
		private set

	/** Since when the running command's runner has looked gone without reporting its end; null while it looks alive. */
	var goneSince: Long? = null

	private var recorder: CommandRecorder? = null

	/**
	 * Whether a line typed now reaches the session's shell rather than a program the user started in
	 * it, given the terminal's [foreground] process group; null means it cannot be told.
	 */
	fun isAtPrompt(foreground: Int?): Boolean = foreground == null || foreground == terminal.pid || foreground == runnerPid

	fun begin(
		command: TerminalCommand,
		typed: Boolean = false,
		startedAt: Long = 0L,
	) {
		this.command = command
		this.typed = typed
		this.startedAt = startedAt
		runnerPid = null
		goneSince = null
		recorder = null
	}

	/** Records what the running command, run by process [runnerPid], prints from now on, rendered as this session renders it. */
	fun startRecording(runnerPid: Int? = null) {
		if (runnerPid != null) this.runnerPid = runnerPid
		val emulator = terminal.emulator
		recorder = CommandRecorder.sizedLike(emulator).also { emulator?.setOutputTap(it) }
	}

	/** Ends the running command with [exitCode], run by process [runnerPid], and returns what it left behind. */
	fun finish(
		exitCode: Int,
		runnerPid: Int? = null,
	): CommandState.Exited {
		terminal.emulator?.setOutputTap(null)
		val exited = CommandState.Exited(checkNotNull(command).id, name, exitCode, output())
		if (runnerPid != null) this.runnerPid = runnerPid
		command = null
		recorder = null
		return exited
	}

	/** The running command and its output so far, or null while the session is idle. */
	fun running(): CommandState.Running? = command?.let { CommandState.Running(it.id, name, output()) }

	private fun output(): String = recorder?.output().orEmpty()
}

/** The Terminal sessions each plugin opened: at most [maxPerPlugin], named "<label> 1", "<label> 2". */
internal class PluginSessionPool(
	private val maxPerPlugin: Int,
) {
	private val byOwner = mutableMapOf<String, MutableList<PluginSession>>()

	/** Where a new command of a plugin can run. */
	sealed interface Slot {
		data class Idle(
			val session: PluginSession,
		) : Slot

		/** No idle session, but room for a new one named [name]. */
		data class Free(
			val name: String,
		) : Slot

		/** Every session is busy, with a command or a program the user started, and there is no room for another. */
		data class Full(
			val busySessionNames: List<String>,
		) : Slot
	}

	/** Forgets the sessions of [owner] that [isOpen] rejects, and returns them. */
	fun prune(
		owner: String,
		isOpen: (TerminalSession) -> Boolean,
	): List<PluginSession> {
		val sessions = byOwner[owner] ?: return emptyList()
		val closed = sessions.filterNot { isOpen(it.terminal) }
		sessions -= closed.toSet()
		// A plugin with no session left keeps no entry.
		if (sessions.isEmpty()) byOwner -= owner
		return closed
	}

	/**
	 * Finds a slot for a command of [owner]. An idle session counts only if [isAtPrompt]; one where
	 * the user started a program of their own is busy. A new session is named after [label]; names
	 * only need to be unique per owner.
	 */
	fun slotFor(
		owner: String,
		label: String = owner,
		isAtPrompt: (PluginSession) -> Boolean = { true },
	): Slot {
		val sessions = byOwner[owner].orEmpty()
		sessions.firstOrNull { it.isIdle && isAtPrompt(it) }?.let { return Slot.Idle(it) }
		if (sessions.size >= maxPerPlugin) return Slot.Full(sessions.map { it.name })
		val taken = sessions.mapTo(mutableSetOf()) { it.name }
		return Slot.Free((1..maxPerPlugin).map { "$label $it" }.first { it !in taken })
	}

	/** The sessions of [owner] running a command. */
	fun busy(owner: String): List<PluginSession> = byOwner[owner].orEmpty().filterNot { it.isIdle }

	fun add(
		owner: String,
		session: PluginSession,
	) {
		byOwner.getOrPut(owner) { mutableListOf() } += session
	}

	fun find(terminal: TerminalSession): PluginSession? = byOwner.values.firstNotNullOfOrNull { sessions -> sessions.firstOrNull { it.terminal == terminal } }

	/** Forgets [terminal], which exited. Returns its plugin session, or null if no plugin opened it. */
	fun remove(terminal: TerminalSession): PluginSession? {
		val session = find(terminal) ?: return null
		byOwner.values.forEach { it.remove(session) }
		byOwner.values.removeAll { it.isEmpty() }
		return session
	}
}
