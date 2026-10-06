package com.itsaky.androidide.terminal

import com.itsaky.androidide.terminal.TerminalCommand.State
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor

/**
 * The commands plugins asked to run in the visible Terminal, and the sessions they run in.
 *
 * The launcher [enqueue]s a command and opens the Terminal with its id, which [CommandIntentRouter]
 * then [claim]s and [start]s in an idle session of the plugin, or a new one. [CommandMarkListener]
 * follows the runner's marks for where the command's output starts and that it exited;
 * [onSessionFinished] covers a session that dies first.
 *
 * Every method must be called on the main thread, where the sessions deliver their output.
 */
class TerminalCommandRequests internal constructor(
	private val runner: AgentRunner,
	maxSessionsPerPlugin: Int,
	/** Where file I/O runs, off the main thread. */
	private val io: Executor,
) {
	// Every command from enqueue until it ends.
	private val commands = mutableMapOf<String, TerminalCommand>()
	private val pool = PluginSessionPool(maxSessionsPerPlugin)
	private val marks = CommandMarkListener(pool, ::exited)

	/** Queues command [id], which the runner prepared, for plugin [owner], whose new sessions are named after [sessionLabel]. */
	fun enqueue(
		id: String,
		workingDirectory: String?,
		owner: String,
		listener: TerminalCommandListener,
		sessionLabel: String = owner,
	) {
		commands[id] = TerminalCommand(id, workingDirectory, owner, listener, sessionLabel)
	}

	/**
	 * Takes queued command [id] for the Terminal, or returns null if it was already claimed or
	 * withdrawn. One-shot, so an activity recreated with the same intent does not run it twice.
	 */
	fun claim(id: String): TerminalCommand? = commands[id]?.takeIf { it.state == State.Queued }?.also { it.state = State.Claimed }

	/**
	 * Withdraws command [id] if the Terminal has not claimed it, reporting [reason].
	 *
	 * @return true if it was withdrawn.
	 */
	fun withdraw(
		id: String,
		reason: TerminalStartFailure,
	): Boolean {
		val command = commands[id]?.takeIf { it.state == State.Queued } ?: return false
		notStarted(command, reason)
		return true
	}

	/**
	 * Runs claimed [command] in an idle session of its plugin, or in a new one from [factory] while
	 * the plugin has room for one.
	 *
	 * @return the session it runs in, or null if it does not run; the listener has been told why,
	 *   unless the command was cancelled.
	 */
	fun start(
		command: TerminalCommand,
		factory: TerminalSessionFactory,
	): TerminalSession? {
		when (command.state) {
			State.Claimed -> Unit
			State.Cancelled -> {
				end(command)
				return null
			}
			State.Queued, is State.Running, State.Ended -> return null
		}

		val session =
			when (val slot = pool.slotFor(command.owner, command.sessionLabel, factory::isOpen)) {
				is PluginSessionPool.Slot.Idle -> slot.session.also { it.terminal.write(runner.typedRunLine(command.id)) }
				is PluginSessionPool.Slot.Free ->
					openSession(command, slot.name, factory) ?: return notStarted(command, TerminalStartFailure.SessionNotCreated)
				is PluginSessionPool.Slot.Full ->
					return notStarted(command, TerminalStartFailure.AllSessionsBusy(slot.busySessionNames))
			}

		session.begin(command)
		session.terminal.setShellIntegrationListener(marks)
		command.state = State.Running(session.terminal)
		command.listener.onStarted(session.name)
		return session.terminal
	}

	/** Interrupts command [id] with Ctrl-C if it runs, or makes sure it never starts. */
	fun cancel(id: String) {
		val command = commands[id] ?: return
		when (val state = command.state) {
			State.Queued -> end(command)
			State.Claimed -> command.state = State.Cancelled
			is State.Running -> state.session.write(ControlKeys.CTRL_C)
			State.Cancelled, State.Ended -> Unit
		}
	}

	/** Command [id] and what it printed so far while it runs; null before it starts and after it ends. */
	fun snapshot(id: String): CommandState.Running? {
		val state = commands[id]?.state as? State.Running ?: return null
		return pool.find(state.session)?.state() as? CommandState.Running
	}

	/** The last command in plugin [owner]'s session [sessionName], or null if it has no command or no such session. */
	fun read(
		owner: String,
		sessionName: String,
	): CommandState? = pool.find(owner, sessionName)?.state()

	/**
	 * Called by the session clients when [terminal] exits. Returns true if it was running a plugin
	 * command; the caller then keeps it open so the user can read what ran.
	 */
	fun onSessionFinished(terminal: TerminalSession): Boolean {
		val session = pool.remove(terminal) ?: return false
		terminal.setShellIntegrationListener(null)
		if (session.isIdle) return false
		exited(session, terminal.exitStatus)
		return true
	}

	/** The sessions plugin [owner] has open. */
	internal fun sessionsOf(owner: String): List<TerminalSession> = pool.sessionsOf(owner).map { it.terminal }

	private fun openSession(
		command: TerminalCommand,
		name: String,
		factory: TerminalSessionFactory,
	): PluginSession? =
		factory
			.open(name, runner.firstRunArguments(command.id), command.workingDirectory)
			?.let(::PluginSession)
			?.also { pool.add(command.owner, it) }

	private fun exited(
		session: PluginSession,
		exitCode: Int,
	) {
		val command = session.command ?: return
		val exited = session.finish(exitCode)
		end(command)
		command.listener.onExited(exited.exitCode, exited.output)
	}

	private fun notStarted(
		command: TerminalCommand,
		reason: TerminalStartFailure,
	): TerminalSession? {
		end(command)
		command.listener.onNotStarted(reason)
		return null
	}

	private fun end(command: TerminalCommand) {
		commands.remove(command.id)
		// A command that never ran leaves its files behind in $TMPDIR until Termux stops.
		if (command.state !is State.Running) io.execute { runner.discard(command.id) }
		command.state = State.Ended
	}

	companion object {
		/** Most sessions one plugin keeps open; a command while all of them are busy is refused. */
		const val MAX_SESSIONS_PER_PLUGIN = 3

		/** The instance the plugin launcher, the intent router and the session clients share. */
		@JvmField
		val shared = TerminalCommandRequests(AgentRunner.termux, MAX_SESSIONS_PER_PLUGIN, Dispatchers.IO.asExecutor())
	}
}
