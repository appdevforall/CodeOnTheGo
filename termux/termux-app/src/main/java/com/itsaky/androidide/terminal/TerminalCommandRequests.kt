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
		val command = queued(id) ?: return false
		notStarted(command, reason)
		return true
	}

	/**
	 * Runs queued command [id] in an idle session of its plugin, or in a new one from [factory] while
	 * the plugin has room for one, then hands [started] the session it runs in, on the main thread.
	 * One-shot, so an activity recreated with the same intent does not run it twice.
	 *
	 * Whether an idle session is at its prompt is read from /proc on [io] first.
	 *
	 * @param screenClosing whether the screen asking is finishing, checked once that read is done; a
	 *   session started then would run the command with nothing on screen, so it is withdrawn instead.
	 * @param started gets null if the command does not run; the listener has been told why, unless it
	 *   was not queued.
	 */
	fun start(
		id: String,
		factory: TerminalSessionFactory,
		screenClosing: () -> Boolean,
		started: (TerminalSession?) -> Unit,
	) {
		val command = queued(id) ?: return started(null)
		pool.prune(command.owner, factory::isOpen).forEach(::closed)
		// Frees, for this command or a later one, a session whose command will never report its end.
		pool.busy(command.owner).forEach(::checkGone)
		val shellPids = pool.idle(command.owner).associateWith { it.terminal.pid }
		io.execute {
			val foreground = shellPids.mapValues { (_, pid) -> processes.foregroundProcessGroup(pid) }
			main.execute { started(place(id, factory, foreground, screenClosing())) }
		}
	}

	private fun place(
		id: String,
		factory: TerminalSessionFactory,
		foreground: Map<PluginSession, Int?>,
		screenClosing: Boolean,
	): TerminalSession? {
		// Withdrawn, cancelled or started elsewhere during the read.
		val command = queued(id) ?: return null
		if (screenClosing) return notStarted(command, TerminalStartFailure.TerminalClosed)

		// A session that went idle during the read is not known to be at its prompt.
		val atPrompt = { session: PluginSession -> session in foreground && session.isAtPrompt(foreground.getValue(session)) }
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

	private fun queued(id: String): TerminalCommand? = commands[id]?.takeIf { it.state == State.Queued }

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
		session.terminal.setShellIntegrationListener(null)
		if (session.isIdle) return false
		// The shell's exit status is not the command's: the command ran in a runner of its own.
		exited(session, CommandMarkListener.UNKNOWN_EXIT_CODE)
		return true
	}

	/**
	 * Ends, with an unknown exit code, the command in [session] if it will never report its end: its
	 * runner was killed, its output swallowed the end mark in an unfinished escape sequence, or a
	 * `read` at the prompt took its typed run line, or the login profile of its new session exec'd
	 * another shell before the runner could start. That holds once the shell has the terminal back
	 * and the runner is gone; the ending lands on a later call, as the end mark may still be coming.
	 */
	private fun checkGone(session: PluginSession) {
		val command = session.command ?: return
		val shellPid = session.terminal.pid
		val runnerPid = session.runnerPid
		val startedAt = session.startedAt
		val typed = session.typed
		io.execute {
			val found = runnerOf(command.id, shellPid, runnerPid, startedAt, typed)
			main.execute { onRunnerChecked(session, command, found) }
		}
	}

	private enum class Runner { ALIVE, GONE, NEVER_STARTED }

	// Off the main thread.
	private fun runnerOf(
		id: String,
		shellPid: Int,
		runnerPid: Int?,
		startedAt: Long,
		typed: Boolean,
	): Runner {
		if (processes.foregroundProcessGroup(shellPid) != shellPid) return Runner.ALIVE
		if (runnerPid != null) return if (processes.parentOf(runnerPid) == shellPid) Runner.ALIVE else Runner.GONE
		// The runner has not reported in. Once it had time to start, the command is taken back so it
		// never runs; a new session's runner starts only after the login profile is sourced.
		if (now() - startedAt < if (typed) GONE_GRACE_MS else FIRST_RUN_GRACE_MS) return Runner.ALIVE
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

		/** How long a new session's first command waits for its runner, which starts once the login profile is sourced. */
		const val FIRST_RUN_GRACE_MS = 30_000L

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
