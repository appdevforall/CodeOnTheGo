package com.itsaky.androidide.terminal

import android.os.SystemClock
import com.itsaky.androidide.terminal.TerminalCommand.State
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.util.concurrent.Executor

/**
 * The commands plugins asked to run in the visible Terminal, and the sessions they run in.
 *
 * The launcher [enqueue]s a command and opens the Terminal with its id, which [CommandIntentRouter]
 * then [start]s in an idle session of the plugin, or a new one. [CommandMarkListener]
 * follows the runner's marks for where the command's output starts and that it exited;
 * [onSessionFinished] covers a session that dies first, and [checkGone] a runner that never
 * reports its end.
 *
 * Every method must be called on the main thread, where the sessions deliver their output.
 */
class TerminalCommandRequests internal constructor(
	private val runner: AgentRunner,
	maxSessionsPerPlugin: Int,
	/** Where file and /proc I/O runs, off the main thread. */
	private val io: Executor,
	/** The main thread, where what that I/O found is acted on. */
	private val main: Executor,
	private val processes: ProcessProbe,
	/** Milliseconds on a clock that never goes back. */
	private val now: () -> Long,
) {
	// Every command from enqueue until it ends.
	private val commands = mutableMapOf<String, TerminalCommand>()
	private val pool = PluginSessionPool(maxSessionsPerPlugin)
	private val marks = CommandMarkListener(pool, ::exited)

	// The last commands of each plugin that exited, by id, oldest first, for read after the session moved on.
	private val exitedByOwner = mutableMapOf<String, LinkedHashMap<String, CommandState.Exited>>()

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
	 * Withdraws command [id] if the Terminal has not started it, reporting [reason].
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
	 * Runs queued command [id] in an idle session of its plugin, or in a new one from [factory] while
	 * the plugin has room for one. One-shot, so an activity recreated with the same intent does not
	 * run it twice.
	 *
	 * @return the session it runs in, or null if it does not run; the listener has been told why,
	 *   unless it was not queued.
	 */
	fun start(
		id: String,
		factory: TerminalSessionFactory,
	): TerminalSession? {
		val command = commands[id]?.takeIf { it.state == State.Queued } ?: return null
		pool.prune(command.owner, factory::isOpen).forEach(::closed)
		// Frees, for a later command, a session whose command will never report its end.
		pool.busy(command.owner).forEach(::checkGone)

		val atPrompt = { session: PluginSession -> session.isAtPrompt(factory.foregroundProcessGroup(session.terminal)) }
		val slot = pool.slotFor(command.owner, command.sessionLabel, atPrompt)
		val session =
			when (slot) {
				is PluginSessionPool.Slot.Idle -> slot.session.also { it.terminal.write(runner.typedRunLine(command.id)) }
				is PluginSessionPool.Slot.Free ->
					openSession(command, slot.name, factory) ?: return notStarted(command, TerminalStartFailure.SessionNotCreated)
				is PluginSessionPool.Slot.Full ->
					return notStarted(command, TerminalStartFailure.AllSessionsBusy(slot.busySessionNames))
			}

		session.begin(command, typed = slot is PluginSessionPool.Slot.Idle, startedAt = now())
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
			is State.Running -> state.session.write(ControlKeys.CTRL_C)
			State.Ended -> Unit
		}
	}

	/**
	 * Ends the session of command [id] if it still runs, for a command that ignored [cancel]. The
	 * session stays on screen, and [onSessionFinished] reports the command's end.
	 */
	fun kill(id: String) {
		(commands[id]?.state as? State.Running)?.session?.finishIfRunning()
	}

	/**
	 * Interrupts plugin [owner]'s command [id] with Ctrl-C if it runs, whichever caller started it.
	 *
	 * @return the listener of the command interrupted, or null if it does not run, or is another plugin's.
	 */
	fun interrupt(
		owner: String,
		id: String,
	): TerminalCommandListener? {
		val command = commands[id]?.takeIf { it.owner == owner } ?: return null
		runningSession(id) ?: return null
		cancel(id)
		return command.listener
	}

	/** Command [id] and what it printed so far while it runs; null before it starts and after it ends. */
	fun snapshot(id: String): CommandState.Running? = runningSession(id)?.running()

	// Each look at a running command also checks that it can still report its end.
	private fun runningSession(id: String): PluginSession? {
		val state = commands[id]?.state as? State.Running ?: return null
		return pool.find(state.session)?.also(::checkGone)
	}

	/**
	 * Plugin [owner]'s command [id]: running, or exited while among the plugin's last
	 * [EXITED_KEPT_PER_PLUGIN] to exit. Null otherwise, and for another plugin's command.
	 */
	fun read(
		owner: String,
		id: String,
	): CommandState? {
		if (commands[id]?.let { it.owner != owner } == true) return null
		// After the snapshot, which can end a command whose runner is gone.
		return snapshot(id) ?: exitedByOwner[owner]?.get(id)
	}

	/**
	 * Called by the session clients when [terminal] exits. Returns true if it was running a plugin
	 * command; the caller then keeps it open so the user can read what ran.
	 */
	fun onSessionFinished(terminal: TerminalSession): Boolean {
		val session = pool.remove(terminal) ?: return false
		return closed(session)
	}

	// Reports the command [session] ran, if any, as ended with the session; true if there was one.
	private fun closed(session: PluginSession): Boolean {
		val terminal = session.terminal
		terminal.setShellIntegrationListener(null)
		if (session.isIdle) return false
		exited(session, if (terminal.isRunning) CommandMarkListener.UNKNOWN_EXIT_CODE else terminal.exitStatus)
		return true
	}

	/**
	 * Ends, with an unknown exit code, the command in [session] if it will never report its end: its
	 * runner was killed, its output swallowed the end mark in an unfinished escape sequence, or a
	 * `read` at the prompt took its typed run line. That holds once the shell has the terminal back
	 * and the runner is gone; the ending lands on a later call, as the end mark may still be coming.
	 */
	private fun checkGone(session: PluginSession) {
		val command = session.command ?: return
		val shellPid = session.terminal.pid
		val runnerPid = session.runnerPid
		val typedAt = session.startedAt.takeIf { session.typed }
		io.execute {
			val found = runnerOf(command.id, shellPid, runnerPid, typedAt)
			main.execute { onRunnerChecked(session, command, found) }
		}
	}

	private enum class Runner { ALIVE, GONE, NEVER_STARTED }

	// Off the main thread.
	private fun runnerOf(
		id: String,
		shellPid: Int,
		runnerPid: Int?,
		typedAt: Long?,
	): Runner {
		if (processes.foregroundProcessGroup(shellPid) != shellPid) return Runner.ALIVE
		if (runnerPid != null) return if (processes.parentOf(runnerPid) == shellPid) Runner.ALIVE else Runner.GONE
		// The runner has not reported in. Only a typed run line can have gone elsewhere, and only
		// once it had time to start is that worth checking.
		if (typedAt == null || now() - typedAt < GONE_GRACE_MS) return Runner.ALIVE
		return if (runner.withdraw(id)) Runner.NEVER_STARTED else Runner.ALIVE
	}

	private fun onRunnerChecked(
		session: PluginSession,
		command: TerminalCommand,
		runner: Runner,
	) {
		// Ended meanwhile, by its end mark or otherwise.
		if (session.command !== command) return
		when (runner) {
			Runner.ALIVE -> session.goneSince = null
			Runner.NEVER_STARTED -> exited(session, CommandMarkListener.UNKNOWN_EXIT_CODE)
			Runner.GONE -> {
				// Its end mark may still be on its way from the session.
				val since = session.goneSince ?: now().also { session.goneSince = it }
				if (now() - since >= GONE_GRACE_MS) exited(session, CommandMarkListener.UNKNOWN_EXIT_CODE)
			}
		}
	}

	private fun openSession(
		command: TerminalCommand,
		name: String,
		factory: TerminalSessionFactory,
	): PluginSession? =
		factory
			.open(name, runner.firstRunArguments(command.id), command.workingDirectory)
			?.let { PluginSession(it, name) }
			?.also { pool.add(command.owner, it) }

	private fun exited(
		session: PluginSession,
		exitCode: Int,
		runnerPid: Int? = null,
	) {
		val command = session.command ?: return
		val exited = session.finish(exitCode, runnerPid)
		val kept = exitedByOwner.getOrPut(command.owner) { LinkedHashMap() }
		kept[command.id] = exited
		while (kept.size > EXITED_KEPT_PER_PLUGIN) kept.remove(kept.keys.first())
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

		/** How many of a plugin's exited commands [read] still finds. */
		const val EXITED_KEPT_PER_PLUGIN = 8

		/** How long a command's runner must look gone, or a typed run line go unanswered, before the command is ended. */
		const val GONE_GRACE_MS = 5_000L

		/** The instance the plugin launcher, the intent router and the session clients share. */
		@JvmField
		val shared =
			TerminalCommandRequests(
				AgentRunner.termux,
				MAX_SESSIONS_PER_PLUGIN,
				Dispatchers.IO.asExecutor(),
				Dispatchers.Main.asExecutor(),
				ProcStat(),
				SystemClock::uptimeMillis,
			)
	}
}
