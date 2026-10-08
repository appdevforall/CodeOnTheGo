package com.itsaky.androidide.terminal

import com.termux.terminal.TerminalSession

/** Opens Terminal sessions for plugin commands; implemented by the Terminal screen. */
interface TerminalSessionFactory {
	/** Whether [session] is still open and its shell running, so it can take another command. */
	fun isOpen(session: TerminalSession): Boolean

	/** Opens a visible session named [name] running bash with [bashArguments], or null if it cannot. */
	fun open(
		name: String,
		bashArguments: Array<String>,
		workingDirectory: String?,
	): TerminalSession?
}

/** A Terminal session a plugin opened: the command it runs now, what that printed, and the last one that exited. */
internal class PluginSession(
	val terminal: TerminalSession,
) {
	val name: String get() = terminal.mSessionName

	/** The command running here, or null while the session is idle. */
	var command: TerminalCommand? = null
		private set

	val isIdle: Boolean get() = command == null

	private var recorder: CommandRecorder? = null
	private var lastExited: CommandState.Exited? = null

	fun begin(command: TerminalCommand) {
		this.command = command
		recorder = null
	}

	/** Records what the running command prints from now on, rendered as this session renders it. */
	fun startRecording() {
		val emulator = terminal.emulator
		recorder = CommandRecorder.sizedLike(emulator).also { emulator?.setOutputTap(it) }
	}

	/** Ends the running command with [exitCode], and returns what it left behind. */
	fun finish(exitCode: Int): CommandState.Exited {
		terminal.emulator?.setOutputTap(null)
		val exited = CommandState.Exited(name, exitCode, output())
		lastExited = exited
		command = null
		recorder = null
		return exited
	}

	/** The running command and its output so far, else the last one that exited, else null. */
	fun state(): CommandState? = if (isIdle) lastExited else CommandState.Running(name, output())

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

		/** Every session runs a command and there is no room for another. */
		data class Full(
			val busySessionNames: List<String>,
		) : Slot
	}

	/**
	 * Finds a slot for a command of [owner], first forgetting its sessions [isOpen] rejects. A new
	 * session is named after [label]; names only need to be unique per owner.
	 */
	fun slotFor(
		owner: String,
		label: String = owner,
		isOpen: (TerminalSession) -> Boolean,
	): Slot {
		val sessions = byOwner[owner]?.apply { retainAll { isOpen(it.terminal) } }.orEmpty()
		// A plugin with no session left keeps no entry.
		if (sessions.isEmpty()) byOwner -= owner
		sessions.firstOrNull { it.isIdle }?.let { return Slot.Idle(it) }
		if (sessions.size >= maxPerPlugin) return Slot.Full(sessions.map { it.name })
		val taken = sessions.mapTo(mutableSetOf()) { it.name }
		return Slot.Free((1..maxPerPlugin).map { "$label $it" }.first { it !in taken })
	}

	fun add(
		owner: String,
		session: PluginSession,
	) {
		byOwner.getOrPut(owner) { mutableListOf() } += session
	}

	/** The open session of [owner] named [name]; never another plugin's. */
	fun find(
		owner: String,
		name: String,
	): PluginSession? = byOwner[owner]?.firstOrNull { it.name == name }

	fun find(terminal: TerminalSession): PluginSession? = byOwner.values.firstNotNullOfOrNull { sessions -> sessions.firstOrNull { it.terminal == terminal } }

	/** Forgets [terminal], which exited. Returns its plugin session, or null if no plugin opened it. */
	fun remove(terminal: TerminalSession): PluginSession? {
		val session = find(terminal) ?: return null
		byOwner.values.forEach { it.remove(session) }
		byOwner.values.removeAll { it.isEmpty() }
		return session
	}
}
